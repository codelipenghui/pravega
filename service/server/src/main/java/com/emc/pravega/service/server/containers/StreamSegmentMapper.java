/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.emc.pravega.service.server.containers;

import java.time.Duration;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import com.emc.pravega.common.Exceptions;
import com.emc.pravega.common.LoggerHelpers;
import com.emc.pravega.common.TimeoutTimer;
import com.emc.pravega.common.concurrent.FutureHelpers;
import com.emc.pravega.service.contracts.SegmentProperties;
import com.emc.pravega.service.contracts.StreamSegmentExistsException;
import com.emc.pravega.service.contracts.StreamSegmentNotExistsException;
import com.emc.pravega.service.server.ContainerMetadata;
import com.emc.pravega.service.server.OperationLog;
import com.emc.pravega.service.server.SegmentMetadata;
import com.emc.pravega.service.server.StreamSegmentNameUtils;
import com.emc.pravega.service.server.logs.operations.StreamSegmentMapOperation;
import com.emc.pravega.service.server.logs.operations.StreamSegmentMapping;
import com.emc.pravega.service.server.logs.operations.TransactionMapOperation;
import com.emc.pravega.service.storage.Storage;
import com.google.common.base.Preconditions;

import lombok.extern.slf4j.Slf4j;

/**
 * Helps assign unique Ids to StreamSegments and persists them in Metadata.
 */
@Slf4j
public class StreamSegmentMapper {
    //region Members

    private final String traceObjectId;
    private final ContainerMetadata containerMetadata;
    private final OperationLog durableLog;
    private final Storage storage;
    private final Executor executor;
    private final HashMap<String, CompletableFuture<Long>> pendingRequests;
    private final Object assignmentLock = new Object();

    //endregion

    //region Constructor

    /**
     * Creates a new instance of the StreamSegmentMapper class.
     *
     * @param containerMetadata The StreamSegmentContainerMetadata to bind to. All assignments are vetted from here,
     *                          but the Metadata is not touched directly from this component.
     * @param durableLog        The Durable Log to bind to. All assignments are durably stored here.
     * @param executor          The executor to use for async operations.
     * @throws NullPointerException If any of the arguments are null.
     */
    public StreamSegmentMapper(ContainerMetadata containerMetadata, OperationLog durableLog, Storage storage, Executor executor) {
        Preconditions.checkNotNull(containerMetadata, "containerMetadata");
        Preconditions.checkNotNull(durableLog, "durableLog");
        Preconditions.checkNotNull(storage, "storage");
        Preconditions.checkNotNull(executor, "executor");

        this.traceObjectId = String.format("StreamSegmentMapper[%d]", containerMetadata.getContainerId());
        this.containerMetadata = containerMetadata;
        this.durableLog = durableLog;
        this.storage = storage;
        this.executor = executor;
        this.pendingRequests = new HashMap<>();
    }

    //endregion

    //region Operations

    /**
     * Creates a new StreamSegment with given name (in Storage) and assigns a unique internal Id to it.
     *
     * @param streamSegmentName The case-sensitive StreamSegment Name.
     * @param timeout           Timeout for the operation.
     * @return A CompletableFuture that, when completed normally, will indicate the operation completed normally.
     * If the operation failed, this will contain the exception that caused the failure.
     */
    public CompletableFuture<Void> createNewStreamSegment(String streamSegmentName, Duration timeout) {
        long traceId = LoggerHelpers.traceEnter(log, traceObjectId, "createNewStreamSegment", streamSegmentName);
        long streamId = this.containerMetadata.getStreamSegmentId(streamSegmentName);
        if (isValidStreamSegmentId(streamId)) {
            return FutureHelpers.failedFuture(new StreamSegmentExistsException("Given StreamSegmentName is already registered internally. Most likely it already exists."));
        }

        // Create the StreamSegment, and then assign a Unique Internal Id to it.
        // Note: this is slightly sub-optimal, as we create the stream, but getOrAssignStreamSegmentId makes another call
        // to get the same info about the StreamSegmentId.
        TimeoutTimer timer = new TimeoutTimer(timeout);
        return this.storage
                .create(streamSegmentName, timer.getRemaining())
                .thenCompose(si -> getOrAssignStreamSegmentId(si.getName(), timer.getRemaining()))
                .thenAccept(id -> LoggerHelpers.traceLeave(log, traceObjectId, "createNewStreamSegment", traceId, streamSegmentName, id));
    }

    /**
     * Creates a new Transaction StreamSegment for an existing Parent StreamSegment and assigns a unique internal Id to it.
     *
     * @param parentStreamSegmentName The case-sensitive StreamSegment Name of the Parent StreamSegment.
     * @param transactionId           A unique identifier for the transaction to be created.
     * @param timeout                 Timeout for the operation.
     * @return A CompletableFuture that, when completed normally, will contain the name of the newly created Transaction StreamSegment.
     * If the operation failed, this will contain the exception that caused the failure.
     * @throws IllegalArgumentException If the given parent StreamSegment cannot have a Transaction (because it is deleted, sealed, inexistent).
     */
    public CompletableFuture<String> createNewTransactionStreamSegment(String parentStreamSegmentName, UUID transactionId, Duration timeout) {
        long traceId = LoggerHelpers.traceEnter(log, traceObjectId, "createNewTransactionStreamSegment", parentStreamSegmentName);

        //We cannot create a Transaction StreamSegment for a what looks like a parent StreamSegment.
        Exceptions.checkArgument(
                StreamSegmentNameUtils.getParentStreamSegmentName(parentStreamSegmentName) == null,
                "parentStreamSegmentName",
                "Given Parent StreamSegmentName looks like a Transaction StreamSegment Name. Cannot create a Transaction for a Transaction.");

        // Validate that Parent StreamSegment exists.
        CompletableFuture<SegmentProperties> parentPropertiesFuture = null;
        long parentStreamSegmentId = this.containerMetadata.getStreamSegmentId(parentStreamSegmentName);
        if (isValidStreamSegmentId(parentStreamSegmentId)) {
            SegmentMetadata parentMetadata = this.containerMetadata.getStreamSegmentMetadata(parentStreamSegmentId);
            if (parentMetadata != null) {
                Exceptions.checkArgument(
                        !isValidStreamSegmentId(parentMetadata.getParentId()),
                        "parentStreamSegmentName",
                        "Given Parent StreamSegment is a Transaction StreamSegment. Cannot create a Transaction for a Transaction.");
                Exceptions.checkArgument(
                        !parentMetadata.isDeleted() && !parentMetadata.isSealed(),
                        "parentStreamSegmentName",
                        "Given Parent StreamSegment is deleted or sealed. Cannot create a Transaction for it.");
                parentPropertiesFuture = CompletableFuture.completedFuture(parentMetadata);
            }
        }

        String transactionName = StreamSegmentNameUtils.getTransactionNameFromId(parentStreamSegmentName, transactionId);
        TimeoutTimer timer = new TimeoutTimer(timeout);
        if (parentPropertiesFuture == null) {
            // We were unable to find this StreamSegment in our metadata. Check in Storage. If the parent StreamSegment
            // does not exist, this will throw an exception (and place it on the resulting future).
            parentPropertiesFuture = this.storage.getStreamSegmentInfo(parentStreamSegmentName, timer.getRemaining());
        }

        return parentPropertiesFuture
                .thenCompose(parentInfo -> this.storage.create(transactionName, timer.getRemaining()))
                .thenCompose(transInfo -> assignTransactionStreamSegmentId(transInfo, parentStreamSegmentId, timer.getRemaining()))
                .thenApply(id -> {
                    LoggerHelpers.traceLeave(log, traceObjectId, "createNewTransactionStreamSegment", traceId, parentStreamSegmentName, transactionName, id);
                    return transactionName;
                });
    }

    /**
     * Attempts to get an existing StreamSegmentId for the given case-sensitive StreamSegment Name.
     * If no such mapping exists, atomically assigns a new one and stores it in the Metadata and DurableLog.
     * <p>
     * If multiple requests for assignment arrive for the same StreamSegment in parallel, the subsequent ones (after the
     * first one) will wait for the first one to complete and return the same result (this will not result in double-assignment).
     * <p>
     * If the given streamSegmentName refers to a Transaction StreamSegment, this will attempt to validate that the Transaction is still
     * valid, by which means it will check the Parent's existence alongside the Transaction's existence.
     *
     * @param streamSegmentName The case-sensitive StreamSegment Name.
     * @param timeout           The timeout for the operation.
     * @return A CompletableFuture that, when completed normally, will contain the StreamSegment Id requested. If the operation
     * failed, this will contain the exception that caused the failure.
     */
    public CompletableFuture<Long> getOrAssignStreamSegmentId(String streamSegmentName, Duration timeout) {
        // Check to see if the metadata already knows about this stream.
        long streamSegmentId = this.containerMetadata.getStreamSegmentId(streamSegmentName);
        if (isValidStreamSegmentId(streamSegmentId)) {
            // We already have a value, just return it (but make sure the Segment has not been deleted).
            if (this.containerMetadata.getStreamSegmentMetadata(streamSegmentId).isDeleted()) {
                return FutureHelpers.failedFuture(new StreamSegmentNotExistsException(streamSegmentName));
            } else {
                return CompletableFuture.completedFuture(streamSegmentId);
            }
        }

        // See if anyone else is currently waiting to get this StreamSegment's id.
        CompletableFuture<Long> result;
        boolean needsAssignment = false;
        synchronized (assignmentLock) {
            result = this.pendingRequests.getOrDefault(streamSegmentName, null);
            if (result == null) {
                needsAssignment = true;
                result = new CompletableFuture<>();
                this.pendingRequests.put(streamSegmentName, result);
            }
        }
        // We are the first/only ones requesting this id; go ahead and assign an id.
        if (needsAssignment) {
            // Determine if given StreamSegmentName is actually a Transaction StreamSegmentName.
            String parentStreamSegmentName = StreamSegmentNameUtils.getParentStreamSegmentName(streamSegmentName);
            if (parentStreamSegmentName == null) {
                // Stand-alone StreamSegment.
                this.executor.execute(() -> assignStreamSegmentId(streamSegmentName, timeout));
            } else {
                this.executor.execute(() -> assignTransactionStreamSegmentId(streamSegmentName, parentStreamSegmentName, timeout));
            }
        }

        return result;
    }

    /**
     * Attempts to map a Transaction StreamSegment to its parent StreamSegment (and assign an id in the process).
     *
     * @param transactionSegmentName The Name for the Transaction to assign Id for.
     * @param parentSegmentName      The Name of the Parent StreamSegment.
     * @param timeout                The timeout for the operation.
     * @return A CompletableFuture that, when completed normally, will contain the StreamSegment Id requested. If the operation
     * failed, this will contain the exception that caused the failure.
     */
    private CompletableFuture<Long> assignTransactionStreamSegmentId(String transactionSegmentName, String parentSegmentName, Duration timeout) {
        TimeoutTimer timer = new TimeoutTimer(timeout);
        AtomicReference<Long> parentSegmentId = new AtomicReference<>();

        // Get info about parent. This also verifies the parent exists.
        return this
                .getOrAssignStreamSegmentId(parentSegmentName, timer.getRemaining())
                .thenCompose(id -> {
                    // Get info about Transaction itself.
                    parentSegmentId.set(id);
                    return this.storage.getStreamSegmentInfo(transactionSegmentName, timer.getRemaining());
                })
                .thenCompose(transInfo -> assignTransactionStreamSegmentId(transInfo, parentSegmentId.get(), timer.getRemaining()))
                .exceptionally(ex -> {
                    failAssignment(transactionSegmentName, ex);
                    throw new CompletionException(ex);
                });
    }

    /**
     * Attempts to map a Transaction StreamSegment to its parent StreamSegment (and assign an id in the process).
     *
     * @param transInfo             The SegmentProperties for the Transaction to assign id for.
     * @param parentStreamSegmentId The ID of the Parent StreamSegment.
     * @param timeout               The timeout for the operation.
     * @return A CompletableFuture that, when completed normally, will contain the StreamSegment Id requested. If the operation
     * failed, this will contain the exception that caused the failure.
     */
    private CompletableFuture<Long> assignTransactionStreamSegmentId(SegmentProperties transInfo, long parentStreamSegmentId, Duration timeout) {
        assert transInfo != null : "transInfo is null";
        assert parentStreamSegmentId != ContainerMetadata.NO_STREAM_SEGMENT_ID : "parentStreamSegmentId is invalid.";
        return persistInDurableLog(transInfo, parentStreamSegmentId, timeout);
    }

    /**
     * Assigns a new Id to the given StreamSegmentName.
     */
    private void assignStreamSegmentId(String streamSegmentName, Duration timeout) {
        TimeoutTimer timer = new TimeoutTimer(timeout);
        this.storage
                .getStreamSegmentInfo(streamSegmentName, timer.getRemaining())
                .thenCompose(streamInfo -> persistInDurableLog(streamInfo, timer.getRemaining()))
                .exceptionally(ex -> {
                    failAssignment(streamSegmentName, ex);
                    throw new CompletionException(ex);
                });
    }

    /**
     * Generates a unique Id for the StreamSegment with given info and persists that in DurableLog.
     *
     * @param streamSegmentInfo The SegmentProperties for the StreamSegment to generate and persist.
     * @param timeout           Timeout for the operation.
     */
    private CompletableFuture<Long> persistInDurableLog(SegmentProperties streamSegmentInfo, Duration timeout) {
        return persistInDurableLog(streamSegmentInfo, ContainerMetadata.NO_STREAM_SEGMENT_ID, timeout);
    }

    /**
     * Generates a unique Id for the StreamSegment with given info and persists that in DurableLog.
     *
     * @param streamSegmentInfo     The SegmentProperties for the StreamSegment to generate and persist.
     * @param parentStreamSegmentId If different from ContainerMetadata.NO_STREAM_SEGMENT_ID, the given streamSegmentInfo
     *                              will be mapped as a f. Otherwise, this will be registered as a standalone StreamSegment.
     * @param timeout               Timeout for the operation.
     */
    private CompletableFuture<Long> persistInDurableLog(SegmentProperties streamSegmentInfo, long parentStreamSegmentId, Duration timeout) {
        if (streamSegmentInfo.isDeleted()) {
            // Stream does not exist. Fail the request with the appropriate exception.
            failAssignment(streamSegmentInfo.getName(), new StreamSegmentNotExistsException("StreamSegment does not exist."));
            return FutureHelpers.failedFuture(new StreamSegmentNotExistsException(streamSegmentInfo.getName()));
        }

        long streamSegmentId = this.containerMetadata.getStreamSegmentId(streamSegmentInfo.getName());
        if (isValidStreamSegmentId(streamSegmentId)) {
            // Looks like someone else beat us to it.
            completeAssignment(streamSegmentInfo.getName(), streamSegmentId);
            return CompletableFuture.completedFuture(streamSegmentId);
        } else {
            CompletableFuture<Long> logAddResult;
            StreamSegmentMapping mapping;
            if (isValidStreamSegmentId(parentStreamSegmentId)) {
                // Transaction.
                SegmentMetadata parentMetadata = this.containerMetadata.getStreamSegmentMetadata(parentStreamSegmentId);
                assert parentMetadata != null : "parentMetadata is null";
                TransactionMapOperation op = new TransactionMapOperation(parentStreamSegmentId, streamSegmentInfo);
                mapping = op;
                logAddResult = this.durableLog.add(op, timeout);
            } else {
                // Standalone StreamSegment.
                StreamSegmentMapOperation op = new StreamSegmentMapOperation(streamSegmentInfo);
                mapping = op;
                logAddResult = this.durableLog.add(op, timeout);
            }

            return logAddResult
                    .thenApply(seqNo -> completeAssignment(streamSegmentInfo.getName(), mapping.getStreamSegmentId()));
        }
    }

    /**
     * Completes the assignment for the given StreamSegmentName by completing the waiting CompletableFuture.
     */
    private long completeAssignment(String streamSegmentName, long streamSegmentId) {
        assert streamSegmentName != null : "no streamSegmentName given";
        assert streamSegmentId != ContainerMetadata.NO_STREAM_SEGMENT_ID : "no valid streamSegmentId given";

        // Get the pending request and complete it.
        CompletableFuture<Long> pendingRequest;
        synchronized (assignmentLock) {
            pendingRequest = this.pendingRequests.getOrDefault(streamSegmentName, null);
            this.pendingRequests.remove(streamSegmentName);
        }

        if (pendingRequest != null) {
            pendingRequest.complete(streamSegmentId);
        }

        return streamSegmentId;
    }

    /**
     * Fails the assignment for the given StreamSegment Id with the given reason.
     */
    private void failAssignment(String streamSegmentName, Throwable reason) {
        assert streamSegmentName != null : "no streamSegmentName given";

        // Get the pending request and complete it.
        CompletableFuture<Long> pendingRequest;
        synchronized (assignmentLock) {
            pendingRequest = this.pendingRequests.getOrDefault(streamSegmentName, null);
            this.pendingRequests.remove(streamSegmentName);
        }

        if (pendingRequest != null) {
            pendingRequest.completeExceptionally(reason);
        }
    }

    private boolean isValidStreamSegmentId(long id) {
        return id != ContainerMetadata.NO_STREAM_SEGMENT_ID;
    }

    //endregion
}