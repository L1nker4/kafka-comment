/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.raft;

import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.feature.SupportedVersionRange;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.raft.internals.BatchAccumulator;
import org.apache.kafka.raft.internals.KRaftControlRecordStateMachine;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;

/**
 * This class is responsible for managing the current state of this node and ensuring
 * only valid state transitions. Below we define the possible state transitions and
 * how they are triggered:
 *
 * Resigned transitions to:
 *    Unattached: After learning of a new election with a higher epoch
 *    Candidate: After expiration of the election timeout
 *    Follower: After discovering a leader with an equal or larger epoch
 *
 * Unattached transitions to:
 *    Unattached: After learning of a new election with a higher epoch or after voting
 *    Candidate: After expiration of the election timeout
 *    Follower: After discovering a leader with an equal or larger epoch
 *
 * Voted transitions to:
 *    Unattached: After learning of a new election with a higher epoch
 *    Candidate: After expiration of the election timeout
 *
 * Candidate transitions to:
 *    Unattached: After learning of a new election with a higher epoch
 *    Candidate: After expiration of the election timeout
 *    Leader: After receiving a majority of votes
 *
 * Leader transitions to:
 *    Unattached: After learning of a new election with a higher epoch
 *    Resigned: When shutting down gracefully
 *
 * Follower transitions to:
 *    Unattached: After learning of a new election with a higher epoch
 *    Candidate: After expiration of the fetch timeout
 *    Follower: After discovering a leader with a larger epoch
 *
 * Observers follow a simpler state machine. The Voted/Candidate/Leader/Resigned
 * states are not possible for observers, so the only transitions that are possible
 * are between Unattached and Follower.
 *
 * Unattached transitions to:
 *    Unattached: After learning of a new election with a higher epoch
 *    Follower: After discovering a leader with an equal or larger epoch
 *
 * Follower transitions to:
 *    Unattached: After learning of a new election with a higher epoch
 *    Follower: After discovering a leader with a larger epoch
 *
 */
public class QuorumState {

    //节点id
    private final OptionalInt localId;

    //目录id
    private final Uuid localDirectoryId;
    private final Time time;
    private final Logger log;

    //用于选举信息存储，JSON格式
    private final QuorumStateStore store;

    //kraft状态机
    private final KRaftControlRecordStateMachine partitionState;

    //各个listener endpoint节点信息
    private final Endpoints localListeners;

    //kraft版本信息
    private final SupportedVersionRange localSupportedKRaftVersion;

    private final Random random;

    //选举超时时间
    private final int electionTimeoutMs;

    //fetch超时时间
    private final int fetchTimeoutMs;

    //日志上下文
    private final LogContext logContext;

    //节点状态对象
    private volatile EpochState state;

    public QuorumState(
        OptionalInt localId,
        Uuid localDirectoryId,
        KRaftControlRecordStateMachine partitionState,
        Endpoints localListeners,
        SupportedVersionRange localSupportedKRaftVersion,
        int electionTimeoutMs,
        int fetchTimeoutMs,
        QuorumStateStore store,
        Time time,
        LogContext logContext,
        Random random
    ) {
        this.localId = localId;
        this.localDirectoryId = localDirectoryId;
        this.partitionState = partitionState;
        this.localListeners = localListeners;
        this.localSupportedKRaftVersion = localSupportedKRaftVersion;
        this.electionTimeoutMs = electionTimeoutMs;
        this.fetchTimeoutMs = fetchTimeoutMs;
        this.store = store;
        this.time = time;
        this.log = logContext.logger(QuorumState.class);
        this.random = random;
        this.logContext = logContext;
    }

    private ElectionState readElectionState() {
        ElectionState election;
        election = store
            .readElectionState()
            .orElseGet(
                () -> ElectionState.withUnknownLeader(0, partitionState.lastVoterSet().voterIds())
            );

        return election;
    }

    public void initialize(OffsetAndEpoch logEndOffsetAndEpoch) throws IllegalStateException {
        // We initialize in whatever state we were in on shutdown. If we were a leader
        // or candidate, probably an election was held, but we will find out about it
        // when we send Vote or BeginEpoch requests.
        ElectionState election = readElectionState();

        final EpochState initialState;
        if (election.hasVoted() && !localId.isPresent()) {
            throw new IllegalStateException(
                String.format(
                    "Initialized quorum state (%s) with a voted candidate but without a local id",
                    election
                )
            );
        } else if (election.epoch() < logEndOffsetAndEpoch.epoch()) {
            log.warn(
                "Epoch from quorum store file ({}) is {}, which is smaller than last written " +
                "epoch {} in the log",
                store.path(),
                election.epoch(),
                logEndOffsetAndEpoch.epoch()
            );
            initialState = new UnattachedState(
                time,
                logEndOffsetAndEpoch.epoch(),
                OptionalInt.empty(),
                Optional.empty(),
                partitionState.lastVoterSet().voterIds(),
                Optional.empty(),
                randomElectionTimeoutMs(),
                logContext
            );
        } else if (localId.isPresent() && election.isLeader(localId.getAsInt())) {
            // If we were previously a leader, then we will start out as resigned
            // in the same epoch. This serves two purposes:
            // 1. It ensures that we cannot vote for another leader in the same epoch.
            // 2. It protects the invariant that each record is uniquely identified by
            //    offset and epoch, which might otherwise be violated if unflushed data
            //    is lost after restarting.
            initialState = new ResignedState(
                time,
                localId.getAsInt(),
                election.epoch(),
                partitionState.lastVoterSet().voterIds(),
                randomElectionTimeoutMs(),
                Collections.emptyList(),
                localListeners,
                logContext
            );
        } else if (
            localId.isPresent() &&
            election.isVotedCandidate(ReplicaKey.of(localId.getAsInt(), localDirectoryId))
        ) {
            initialState = new CandidateState(
                time,
                localId.getAsInt(),
                localDirectoryId,
                election.epoch(),
                partitionState.lastVoterSet(),
                Optional.empty(),
                1,
                randomElectionTimeoutMs(),
                logContext
            );
        } else if (election.hasVoted()) {
            initialState = new UnattachedState(
                time,
                election.epoch(),
                OptionalInt.empty(),
                Optional.of(election.votedKey()),
                partitionState.lastVoterSet().voterIds(),
                Optional.empty(),
                randomElectionTimeoutMs(),
                logContext
            );
        } else if (election.hasLeader()) {
            VoterSet voters = partitionState.lastVoterSet();
            Endpoints leaderEndpoints = voters.listeners(election.leaderId());
            if (leaderEndpoints.isEmpty()) {
                // Since the leader's endpoints are not known, it cannot send Fetch or
                // FetchSnapshot requests to the leader.
                //
                // Transition to unattached instead and discover the leader's endpoint through
                // Fetch requests to the bootstrap servers or from a BeginQuorumEpoch request from
                // the leader.
                log.info(
                    "The leader in election state {} is not a member of the latest voter set {}; " +
                    "transitioning to unattached instead of follower because the leader's " +
                    "endpoints are not known",
                    election,
                    voters
                );

                initialState = new UnattachedState(
                    time,
                    election.epoch(),
                    OptionalInt.of(election.leaderId()),
                    Optional.empty(),
                    partitionState.lastVoterSet().voterIds(),
                    Optional.empty(),
                    randomElectionTimeoutMs(),
                    logContext
                );
            } else {
                initialState = new FollowerState(
                    time,
                    election.epoch(),
                    election.leaderId(),
                    leaderEndpoints,
                    voters.voterIds(),
                    Optional.empty(),
                    fetchTimeoutMs,
                    logContext
                );
            }
        } else {
            initialState = new UnattachedState(
                time,
                election.epoch(),
                OptionalInt.empty(),
                Optional.empty(),
                partitionState.lastVoterSet().voterIds(),
                Optional.empty(),
                randomElectionTimeoutMs(),
                logContext
            );
        }

        durableTransitionTo(initialState);
    }

    public boolean isOnlyVoter() {
        return localId.isPresent() &&
            partitionState
                .lastVoterSet()
                .isOnlyVoter(ReplicaKey.of(localId.getAsInt(), localDirectoryId));
    }

    public int localIdOrSentinel() {
        return localId.orElse(-1);
    }

    public int localIdOrThrow() {
        return localId.orElseThrow(() -> new IllegalStateException("Required local id is not present"));
    }

    public OptionalInt localId() {
        return localId;
    }

    public Uuid localDirectoryId() {
        return localDirectoryId;
    }

    public ReplicaKey localReplicaKeyOrThrow() {
        return ReplicaKey.of(localIdOrThrow(), localDirectoryId());
    }

    public VoterSet.VoterNode localVoterNodeOrThrow() {
        return VoterSet.VoterNode.of(
            localReplicaKeyOrThrow(),
            localListeners,
            localSupportedKRaftVersion
        );
    }

    public int epoch() {
        return state.epoch();
    }

    public int leaderIdOrSentinel() {
        return state.election().leaderIdOrSentinel();
    }

    public Optional<LogOffsetMetadata> highWatermark() {
        return state.highWatermark();
    }

    public OptionalInt leaderId() {
        ElectionState election = state.election();
        if (election.hasLeader())
            return OptionalInt.of(state.election().leaderId());
        else
            return OptionalInt.empty();
    }

    public boolean hasLeader() {
        return leaderId().isPresent();
    }

    public boolean hasRemoteLeader() {
        return hasLeader() && leaderIdOrSentinel() != localIdOrSentinel();
    }

    public Endpoints leaderEndpoints() {
        return state.leaderEndpoints();
    }

    public boolean isVoter() {
        if (!localId.isPresent()) {
            return false;
        }

        return partitionState
            .lastVoterSet()
            .isVoter(ReplicaKey.of(localId.getAsInt(), localDirectoryId));
    }

    public boolean isVoter(ReplicaKey nodeKey) {
        return partitionState.lastVoterSet().isVoter(nodeKey);
    }

    public boolean isObserver() {
        return !isVoter();
    }

    public void transitionToResigned(List<ReplicaKey> preferredSuccessors) {
        if (!isLeader()) {
            throw new IllegalStateException("Invalid transition to Resigned state from " + state);
        }

        // The Resigned state is a soft state which does not need to be persisted.
        // A leader will always be re-initialized in this state.
        int epoch = state.epoch();
        memoryTransitionTo(
            new ResignedState(
                time,
                localIdOrThrow(),
                epoch,
                partitionState.lastVoterSet().voterIds(),
                randomElectionTimeoutMs(),
                preferredSuccessors,
                localListeners,
                logContext
            )
        );
    }

    /**
     * Transition to the "unattached" state. This means we have found an epoch greater than the current epoch,
     * but we do not yet know of the elected leader.
     */
    public void transitionToUnattached(int epoch) {
        int currentEpoch = state.epoch();
        if (epoch <= currentEpoch) {
            throw new IllegalStateException("Cannot transition to Unattached with epoch= " + epoch +
                " from current state " + state);
        }

        final long electionTimeoutMs;
        if (isObserver()) {
            electionTimeoutMs = Long.MAX_VALUE;
        } else if (isCandidate()) {
            electionTimeoutMs = candidateStateOrThrow().remainingElectionTimeMs(time.milliseconds());
        } else if (isUnattached()) {
            electionTimeoutMs = unattachedStateOrThrow().remainingElectionTimeMs(time.milliseconds());
        } else {
            electionTimeoutMs = randomElectionTimeoutMs();
        }

        durableTransitionTo(new UnattachedState(
            time,
            epoch,
            OptionalInt.empty(),
            Optional.empty(),
            partitionState.lastVoterSet().voterIds(),
            state.highWatermark(),
            electionTimeoutMs,
            logContext
        ));
    }

    /**
     * Grant a vote to a candidate. We will transition/remain in Unattached
     * state until either the election timeout expires or a leader is elected. In particular,
     * we do not begin fetching until the election has concluded and
     * {@link #transitionToFollower(int, int, Endpoints)} is invoked.
     */
    public void transitionToUnattachedVotedState(
        int epoch,
        ReplicaKey candidateKey
    ) {
        int currentEpoch = state.epoch();
        if (localId.isPresent() && candidateKey.id() == localId.getAsInt()) {
            throw new IllegalStateException(
                String.format(
                    "Cannot transition to Voted for %s and epoch %d since it matches the local " +
                    "broker.id",
                    candidateKey,
                    epoch
                )
            );
        } else if (!localId.isPresent()) {
            throw new IllegalStateException("Cannot transition to voted without a replica id");
        } else if (epoch < currentEpoch) {
            throw new IllegalStateException(
                String.format(
                    "Cannot transition to Voted for %s and epoch %d since the current epoch " +
                    "(%d) is larger",
                    candidateKey,
                    epoch,
                    currentEpoch
                )
            );
        } else if (epoch == currentEpoch && !isUnattachedNotVoted()) {
            throw new IllegalStateException(
                String.format(
                    "Cannot transition to Voted for %s and epoch %d from the current state (%s)",
                    candidateKey,
                    epoch,
                    state
                )
            );
        }

        // Note that we reset the election timeout after voting for a candidate because we
        // know that the candidate has at least as good of a chance of getting elected as us
        durableTransitionTo(
            new UnattachedState(
                time,
                epoch,
                OptionalInt.empty(),
                Optional.of(candidateKey),
                partitionState.lastVoterSet().voterIds(),
                state.highWatermark(),
                randomElectionTimeoutMs(),
                logContext
            )
        );
        log.debug("Voted for candidate {} in epoch {}", candidateKey, epoch);
    }

    /**
     * Become a follower of an elected leader so that we can begin fetching.
     */
    public void transitionToFollower(int epoch, int leaderId, Endpoints endpoints) {
        int currentEpoch = state.epoch();
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException(
                String.format(
                    "Cannot transition to Follower with leader %s and epoch %s without a leader endpoint",
                    leaderId,
                    epoch
                )
            );
        } else if (localId.isPresent() && leaderId == localId.getAsInt()) {
            throw new IllegalStateException(
                String.format(
                    "Cannot transition to Follower with leader %s and epoch %s since it matches the local node.id %s",
                    leaderId,
                    epoch,
                    localId
                )
            );
        } else if (epoch < currentEpoch) {
            throw new IllegalStateException(
                String.format(
                    "Cannot transition to Follower with leader %s and epoch %s since the current epoch %s is larger",
                    leaderId,
                    epoch,
                    currentEpoch
                )
            );
        } else if (epoch == currentEpoch) {
            if (isFollower() && state.leaderEndpoints().size() >= endpoints.size()) {
                throw new IllegalStateException(
                    String.format(
                        "Cannot transition to Follower with leader %s, epoch %s and endpoints %s from state %s",
                        leaderId,
                        epoch,
                        endpoints,
                        state
                    )
                );
            } else if (isLeader()) {
                throw new IllegalStateException(
                    String.format(
                        "Cannot transition to Follower with leader %s and epoch %s from state %s",
                        leaderId,
                        epoch,
                        state
                    )
                );
            }
        }

        durableTransitionTo(
            new FollowerState(
                time,
                epoch,
                leaderId,
                endpoints,
                partitionState.lastVoterSet().voterIds(),
                state.highWatermark(),
                fetchTimeoutMs,
                logContext
            )
        );
    }

    public void transitionToCandidate() {
        if (isObserver()) {
            throw new IllegalStateException(
                String.format(
                    "Cannot transition to Candidate since the local id (%s) and directory id (%s) " +
                    "is not one of the voters %s",
                    localId,
                    localDirectoryId,
                    partitionState.lastVoterSet()
                )
            );
        } else if (isLeader()) {
            throw new IllegalStateException("Cannot transition to Candidate since the local broker.id=" + localId +
                " since this node is already a Leader with state " + state);
        }

        int retries = isCandidate() ? candidateStateOrThrow().retries() + 1 : 1;
        int newEpoch = epoch() + 1;
        int electionTimeoutMs = randomElectionTimeoutMs();

        durableTransitionTo(new CandidateState(
            time,
            localIdOrThrow(),
            localDirectoryId,
            newEpoch,
            partitionState.lastVoterSet(),
            state.highWatermark(),
            retries,
            electionTimeoutMs,
            logContext
        ));
    }

    public <T> LeaderState<T> transitionToLeader(long epochStartOffset, BatchAccumulator<T> accumulator) {
        //1.1 如果是observer或不是candidate，则抛出异常
        if (isObserver()) {
            throw new IllegalStateException(
                String.format(
                    "Cannot transition to Leader since the local id (%s) and directory id (%s) " +
                    "is not one of the voters %s",
                    localId,
                    localDirectoryId,
                    partitionState.lastVoterSet()
                )
            );
        } else if (!isCandidate()) {
            throw new IllegalStateException("Cannot transition to Leader from current state " + state);
        }

        //1.2 获取candidateState
        CandidateState candidateState = candidateStateOrThrow();
        if (!candidateState.isVoteGranted())
            throw new IllegalStateException("Cannot become leader without majority votes granted");

        // Note that the leader does not retain the high watermark that was known
        // in the previous state. The reason for this is to protect the monotonicity
        // of the global high watermark, which is exposed through the leader. The
        // only way a new leader can be sure that the high watermark is increasing
        // monotonically is to wait until a majority of the voters have reached the
        // starting offset of the new epoch. The downside of this is that the local
        // state machine is temporarily stalled by the advancement of the global
        // high watermark even though it only depends on local monotonicity. We
        // could address this problem by decoupling the local high watermark, but
        // we typically expect the state machine to be caught up anyway.


        //2.1 创建LeaderState对象
        LeaderState<T> state = new LeaderState<>(
            time,
            ReplicaKey.of(localIdOrThrow(), localDirectoryId),
            epoch(),
            epochStartOffset,
            partitionState.lastVoterSet(),
            partitionState.lastVoterSetOffset(),
            partitionState.lastKraftVersion(),
            candidateState.grantingVoters(),
            accumulator,
            localListeners,
            fetchTimeoutMs,
            logContext
        );

        //2.2 更新
        durableTransitionTo(state);
        return state;
    }

    private void durableTransitionTo(EpochState newState) {
        log.info("Attempting durable transition to {} from {}", newState, state);
        //写入文件
        store.writeElectionState(newState.election(), partitionState.lastKraftVersion());
        //更新内存
        memoryTransitionTo(newState);
    }

    private void memoryTransitionTo(EpochState newState) {
        if (state != null) {
            try {
                state.close();
            } catch (IOException e) {
                throw new UncheckedIOException(
                    "Failed to transition from " + state.name() + " to " + newState.name(), e);
            }
        }

        EpochState from = state;
        state = newState;
        log.info("Completed transition to {} from {}", newState, from);
    }

    private int randomElectionTimeoutMs() {
        if (electionTimeoutMs == 0)
            return 0;
        return electionTimeoutMs + random.nextInt(electionTimeoutMs);
    }

    public boolean canGrantVote(ReplicaKey candidateKey, boolean isLogUpToDate) {
        return state.canGrantVote(candidateKey, isLogUpToDate);
    }

    public FollowerState followerStateOrThrow() {
        if (isFollower())
            return (FollowerState) state;
        throw new IllegalStateException("Expected to be Follower, but the current state is " + state);
    }

    public Optional<UnattachedState> maybeUnattachedState() {
        EpochState fixedState = state;
        if (fixedState instanceof UnattachedState) {
            return Optional.of((UnattachedState) fixedState);
        } else {
            return Optional.empty();
        }
    }

    public UnattachedState unattachedStateOrThrow() {
        if (isUnattached())
            return (UnattachedState) state;
        throw new IllegalStateException("Expected to be Unattached, but current state is " + state);
    }

    public <T> LeaderState<T> leaderStateOrThrow() {
        return this.<T>maybeLeaderState()
            .orElseThrow(() -> new IllegalStateException("Expected to be Leader, but current state is " + state));
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<LeaderState<T>> maybeLeaderState() {
        EpochState fixedState = state;
        if (fixedState instanceof LeaderState) {
            return Optional.of((LeaderState<T>) fixedState);
        } else {
            return Optional.empty();
        }
    }

    public ResignedState resignedStateOrThrow() {
        if (isResigned())
            return (ResignedState) state;
        throw new IllegalStateException("Expected to be Resigned, but current state is " + state);
    }

    public CandidateState candidateStateOrThrow() {
        if (isCandidate())
            return (CandidateState) state;
        throw new IllegalStateException("Expected to be Candidate, but current state is " + state);
    }

    public LeaderAndEpoch leaderAndEpoch() {
        ElectionState election = state.election();
        return new LeaderAndEpoch(election.optionalLeaderId(), election.epoch());
    }

    public boolean isFollower() {
        return state instanceof FollowerState;
    }

    public boolean isUnattached() {
        return state instanceof UnattachedState;
    }

    public boolean isUnattachedNotVoted() {
        return maybeUnattachedState().filter(unattached -> !unattached.votedKey().isPresent()).isPresent();
    }

    public boolean isUnattachedAndVoted() {
        return maybeUnattachedState().flatMap(UnattachedState::votedKey).isPresent();
    }

    public boolean isLeader() {
        return state instanceof LeaderState;
    }

    public boolean isResigned() {
        return state instanceof ResignedState;
    }

    public boolean isCandidate() {
        return state instanceof CandidateState;
    }
}
