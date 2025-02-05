/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.table.impl;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.naming.OperationNotSupportedException;
import org.apache.ignite.configuration.ConfigurationValue;
import org.apache.ignite.distributed.TestPartitionDataStorage;
import org.apache.ignite.internal.TestHybridClock;
import org.apache.ignite.internal.catalog.CatalogService;
import org.apache.ignite.internal.catalog.descriptors.CatalogTableDescriptor;
import org.apache.ignite.internal.hlc.HybridClock;
import org.apache.ignite.internal.hlc.HybridTimestamp;
import org.apache.ignite.internal.lang.IgniteInternalException;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.logger.Loggers;
import org.apache.ignite.internal.placementdriver.TestPlacementDriver;
import org.apache.ignite.internal.raft.Command;
import org.apache.ignite.internal.raft.Peer;
import org.apache.ignite.internal.raft.WriteCommand;
import org.apache.ignite.internal.raft.service.CommandClosure;
import org.apache.ignite.internal.raft.service.LeaderWithTerm;
import org.apache.ignite.internal.raft.service.RaftGroupService;
import org.apache.ignite.internal.replicator.ReplicaResult;
import org.apache.ignite.internal.replicator.ReplicaService;
import org.apache.ignite.internal.replicator.ReplicationGroupId;
import org.apache.ignite.internal.replicator.TablePartitionId;
import org.apache.ignite.internal.replicator.listener.ReplicaListener;
import org.apache.ignite.internal.schema.BinaryRow;
import org.apache.ignite.internal.schema.BinaryRowConverter;
import org.apache.ignite.internal.schema.BinaryRowEx;
import org.apache.ignite.internal.schema.Column;
import org.apache.ignite.internal.schema.ColumnsExtractor;
import org.apache.ignite.internal.schema.SchemaDescriptor;
import org.apache.ignite.internal.schema.configuration.GcConfiguration;
import org.apache.ignite.internal.storage.MvPartitionStorage;
import org.apache.ignite.internal.storage.engine.MvTableStorage;
import org.apache.ignite.internal.storage.impl.TestMvPartitionStorage;
import org.apache.ignite.internal.storage.index.StorageHashIndexDescriptor;
import org.apache.ignite.internal.storage.index.StorageHashIndexDescriptor.StorageHashIndexColumnDescriptor;
import org.apache.ignite.internal.storage.index.impl.TestHashIndexStorage;
import org.apache.ignite.internal.table.distributed.HashIndexLocker;
import org.apache.ignite.internal.table.distributed.IndexLocker;
import org.apache.ignite.internal.table.distributed.LowWatermark;
import org.apache.ignite.internal.table.distributed.StorageUpdateHandler;
import org.apache.ignite.internal.table.distributed.TableIndexStoragesSupplier;
import org.apache.ignite.internal.table.distributed.TableSchemaAwareIndexStorage;
import org.apache.ignite.internal.table.distributed.gc.GcUpdateHandler;
import org.apache.ignite.internal.table.distributed.index.IndexUpdateHandler;
import org.apache.ignite.internal.table.distributed.raft.PartitionDataStorage;
import org.apache.ignite.internal.table.distributed.raft.PartitionListener;
import org.apache.ignite.internal.table.distributed.replicator.PartitionReplicaListener;
import org.apache.ignite.internal.table.distributed.replicator.TransactionStateResolver;
import org.apache.ignite.internal.table.distributed.schema.AlwaysSyncedSchemaSyncService;
import org.apache.ignite.internal.table.distributed.storage.InternalTableImpl;
import org.apache.ignite.internal.tx.HybridTimestampTracker;
import org.apache.ignite.internal.tx.InternalTransaction;
import org.apache.ignite.internal.tx.TxManager;
import org.apache.ignite.internal.tx.impl.HeapLockManager;
import org.apache.ignite.internal.tx.impl.TransactionIdGenerator;
import org.apache.ignite.internal.tx.impl.TxManagerImpl;
import org.apache.ignite.internal.tx.storage.state.test.TestTxStateTableStorage;
import org.apache.ignite.internal.type.NativeTypes;
import org.apache.ignite.internal.util.Lazy;
import org.apache.ignite.internal.util.PendingComparableValuesTracker;
import org.apache.ignite.internal.util.PendingIndependentComparableValuesTracker;
import org.apache.ignite.network.ClusterNode;
import org.apache.ignite.network.ClusterNodeImpl;
import org.apache.ignite.network.NetworkAddress;
import org.apache.ignite.tx.TransactionException;
import org.jetbrains.annotations.Nullable;

/**
 * Dummy table storage implementation.
 */
public class DummyInternalTableImpl extends InternalTableImpl {
    public static final IgniteLogger LOG = Loggers.forClass(DummyInternalTableImpl.class);

    public static final NetworkAddress ADDR = new NetworkAddress("127.0.0.1", 2004);

    public static final ClusterNode LOCAL_NODE = new ClusterNodeImpl("id", "node", ADDR);

    private static final TestPlacementDriver TEST_PLACEMENT_DRIVER = new TestPlacementDriver(LOCAL_NODE.name());

    // 2000 was picked to avoid negative time that we get when building read timestamp
    // in TxManagerImpl.currentReadTimestamp.
    // We subtract (ReplicaManager.IDLE_SAFE_TIME_PROPAGATION_PERIOD_MILLISECONDS + HybridTimestamp.CLOCK_SKEW) = (1000 + 7) = 1007
    // from the current time.
    // Any value greater than that will work, hence 2000.
    public static final HybridClock CLOCK = new TestHybridClock(() -> 2000);

    private static final int PART_ID = 0;

    private static final SchemaDescriptor SCHEMA = new SchemaDescriptor(
            1,
            new Column[]{new Column("key", NativeTypes.INT64, false)},
            new Column[]{new Column("value", NativeTypes.INT64, false)}
    );

    private static final ReplicationGroupId crossTableGroupId = new TablePartitionId(333, 0);

    private PartitionListener partitionListener;

    private ReplicaListener replicaListener;

    private final ReplicationGroupId groupId;

    /** The thread updates safe time on the dummy replica. */
    private final PendingComparableValuesTracker<HybridTimestamp, Void> safeTime;

    private final Object raftServiceMutex = new Object();

    private static final AtomicInteger nextTableId = new AtomicInteger(10_001);

    /**
     * Creates a new local table.
     *
     * @param replicaSvc Replica service.
     */
    public DummyInternalTableImpl(ReplicaService replicaSvc) {
        this(replicaSvc, SCHEMA);
    }

    /**
     * Creates a new local table.
     *
     * @param replicaSvc Replica service.
     * @param schema Schema.
     */
    public DummyInternalTableImpl(ReplicaService replicaSvc, SchemaDescriptor schema) {
        this(replicaSvc, new TestMvPartitionStorage(0), schema);
    }

    /**
     * Creates a new local table.
     *
     * @param replicaSvc Replica service.
     * @param txManager Transaction manager.
     * @param crossTableUsage If this dummy table is going to be used in cross-table tests, it won't mock the calls of
     *         ReplicaService by itself.
     * @param transactionStateResolver Transaction state resolver.
     * @param schema Schema descriptor.
     * @param tracker Observable timestamp tracker.
     */
    public DummyInternalTableImpl(
            ReplicaService replicaSvc,
            TxManager txManager,
            boolean crossTableUsage,
            @Nullable TransactionStateResolver transactionStateResolver,
            SchemaDescriptor schema,
            HybridTimestampTracker tracker
    ) {
        this(replicaSvc, new TestMvPartitionStorage(0), txManager, crossTableUsage, transactionStateResolver, schema, tracker);
    }

    /**
     * Creates a new local table.
     *
     * @param replicaSvc Replica service.
     * @param mvPartStorage Multi version partition storage.
     * @param schema Schema descriptor.
     */
    public DummyInternalTableImpl(
            ReplicaService replicaSvc,
            MvPartitionStorage mvPartStorage,
            SchemaDescriptor schema
    ) {
        this(
                replicaSvc,
                mvPartStorage,
                txManager(replicaSvc),
                false,
                null,
                schema,
                new HybridTimestampTracker()
        );
    }

    /**
     * Creates a new local table.
     *
     * @param replicaSvc Replica service.
     * @param mvPartStorage Multi version partition storage.
     * @param txManager Transaction manager.
     * @param crossTableUsage If this dummy table is going to be used in cross-table tests, it won't mock the calls of
     *         ReplicaService by itself.
     * @param transactionStateResolver Transaction state resolver.
     * @param schema Schema descriptor.
     * @param tracker Observable timestamp tracker.
     */
    public DummyInternalTableImpl(
            ReplicaService replicaSvc,
            MvPartitionStorage mvPartStorage,
            TxManager txManager,
            boolean crossTableUsage,
            @Nullable TransactionStateResolver transactionStateResolver,
            SchemaDescriptor schema,
            HybridTimestampTracker tracker
    ) {
        super(
                "test",
                nextTableId.getAndIncrement(),
                Int2ObjectMaps.singleton(PART_ID, mock(RaftGroupService.class)),
                1,
                name -> LOCAL_NODE,
                txManager,
                mock(MvTableStorage.class),
                new TestTxStateTableStorage(),
                replicaSvc,
                CLOCK,
                tracker,
                TEST_PLACEMENT_DRIVER
        );
        RaftGroupService svc = raftGroupServiceByPartitionId.get(PART_ID);

        groupId = crossTableUsage ? new TablePartitionId(tableId(), PART_ID) : crossTableGroupId;

        lenient().doReturn(groupId).when(svc).groupId();
        Peer leaderPeer = new Peer(UUID.randomUUID().toString());
        lenient().doReturn(leaderPeer).when(svc).leader();
        lenient().doReturn(completedFuture(new LeaderWithTerm(leaderPeer, 1L))).when(svc).refreshAndGetLeaderWithTerm();

        if (!crossTableUsage) {
            // Delegate replica requests directly to replica listener.
            lenient()
                    .doAnswer(invocationOnMock -> {
                        ClusterNode node = invocationOnMock.getArgument(0);

                        return replicaListener.invoke(invocationOnMock.getArgument(1), node.id()).thenApply(ReplicaResult::result);
                    })
                    .when(replicaSvc).invoke(any(ClusterNode.class), any());

            lenient()
                    .doAnswer(invocationOnMock -> {
                        String nodeId = invocationOnMock.getArgument(0);

                        return replicaListener.invoke(invocationOnMock.getArgument(1), nodeId).thenApply(ReplicaResult::result);
                    })
                    .when(replicaSvc).invoke(anyString(), any());
        }

        AtomicLong raftIndex = new AtomicLong();

        // Delegate directly to listener.
        lenient().doAnswer(
                invocationClose -> {
                    synchronized (raftServiceMutex) {
                        Command cmd = invocationClose.getArgument(0);

                        long commandIndex = raftIndex.incrementAndGet();

                        CompletableFuture<Serializable> res = new CompletableFuture<>();

                        // All read commands are handled directly throw partition replica listener.
                        CommandClosure<WriteCommand> clo = new CommandClosure<>() {
                            /** {@inheritDoc} */
                            @Override
                            public long index() {
                                return commandIndex;
                            }

                            /** {@inheritDoc} */
                            @Override
                            public WriteCommand command() {
                                return (WriteCommand) cmd;
                            }

                            /** {@inheritDoc} */
                            @Override
                            public void result(@Nullable Serializable r) {
                                if (r instanceof Throwable) {
                                    res.completeExceptionally((Throwable) r);
                                } else {
                                    res.complete(r);
                                }
                            }
                        };

                        try {
                            partitionListener.onWrite(List.of(clo).iterator());
                        } catch (Throwable e) {
                            res.completeExceptionally(new TransactionException(e));
                        }

                        return res;
                    }
                }
        ).when(svc).run(any());

        int tableId = tableId();
        int indexId = 1;

        ColumnsExtractor row2Tuple = BinaryRowConverter.keyExtractor(schema);

        StorageHashIndexDescriptor pkIndexDescriptor = mock(StorageHashIndexDescriptor.class);

        when(pkIndexDescriptor.columns()).then(
                invocation -> Collections.nCopies(schema.keyColumns().columns().length, mock(StorageHashIndexColumnDescriptor.class))
        );

        Lazy<TableSchemaAwareIndexStorage> pkStorage = new Lazy<>(() -> new TableSchemaAwareIndexStorage(
                indexId,
                new TestHashIndexStorage(PART_ID, pkIndexDescriptor),
                row2Tuple
        ));

        IndexLocker pkLocker = new HashIndexLocker(indexId, true, this.txManager.lockManager(), row2Tuple);

        safeTime = new PendingIndependentComparableValuesTracker<>(HybridTimestamp.MIN_VALUE);

        PartitionDataStorage partitionDataStorage = new TestPartitionDataStorage(tableId, PART_ID, mvPartStorage);
        TableIndexStoragesSupplier indexes = createTableIndexStoragesSupplier(Map.of(pkStorage.get().id(), pkStorage.get()));

        GcConfiguration gcConfig = mock(GcConfiguration.class);
        ConfigurationValue<Integer> gcBatchSizeValue = mock(ConfigurationValue.class);
        lenient().when(gcBatchSizeValue.value()).thenReturn(5);
        lenient().when(gcConfig.onUpdateBatchSize()).thenReturn(gcBatchSizeValue);

        IndexUpdateHandler indexUpdateHandler = new IndexUpdateHandler(indexes);

        StorageUpdateHandler storageUpdateHandler = new StorageUpdateHandler(
                PART_ID,
                partitionDataStorage,
                gcConfig,
                mock(LowWatermark.class),
                indexUpdateHandler,
                new GcUpdateHandler(partitionDataStorage, safeTime, indexUpdateHandler)
        );

        DummySchemaManagerImpl schemaManager = new DummySchemaManagerImpl(schema);

        CatalogService catalogService = mock(CatalogService.class);
        CatalogTableDescriptor tableDescriptor = mock(CatalogTableDescriptor.class);

        lenient().when(catalogService.table(anyInt(), anyLong())).thenReturn(tableDescriptor);
        lenient().when(tableDescriptor.tableVersion()).thenReturn(1);

        replicaListener = new PartitionReplicaListener(
                mvPartStorage,
                raftGroupServiceByPartitionId.get(PART_ID),
                this.txManager,
                this.txManager.lockManager(),
                Runnable::run,
                PART_ID,
                tableId,
                () -> Map.of(pkLocker.id(), pkLocker),
                pkStorage,
                Map::of,
                CLOCK,
                safeTime,
                txStateStorage().getOrCreateTxStateStorage(PART_ID),
                transactionStateResolver,
                storageUpdateHandler,
                new DummySchemas(schemaManager),
                LOCAL_NODE,
                new AlwaysSyncedSchemaSyncService(),
                catalogService,
                TEST_PLACEMENT_DRIVER
        );

        partitionListener = new PartitionListener(
                this.txManager,
                new TestPartitionDataStorage(tableId, PART_ID, mvPartStorage),
                storageUpdateHandler,
                txStateStorage().getOrCreateTxStateStorage(PART_ID),
                safeTime,
                new PendingComparableValuesTracker<>(0L)
        );
    }

    /**
     * Replica listener.
     *
     * @return Replica listener.
     */
    public ReplicaListener getReplicaListener() {
        return replicaListener;
    }

    /**
     * Group id of single partition of this table.
     *
     * @return Group id.
     */
    public ReplicationGroupId groupId() {
        return groupId;
    }

    /**
     * Gets the transaction manager that is bound to the table.
     *
     * @return Transaction manager.
     */
    public TxManager txManager() {
        return txManager;
    }

    /**
     * Creates a {@link TxManager}.
     *
     * @param replicaSvc Replica service to use.
     */
    public static TxManagerImpl txManager(ReplicaService replicaSvc) {
        return new TxManagerImpl(
                replicaSvc,
                new HeapLockManager(),
                CLOCK,
                new TransactionIdGenerator(0xdeadbeef),
                LOCAL_NODE::id,
                TEST_PLACEMENT_DRIVER
        );
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<BinaryRow> get(BinaryRowEx keyRow, InternalTransaction tx) {
        return super.get(keyRow, tx);
    }

    /** {@inheritDoc} */
    @Override
    public List<String> assignments() {
        throw new IgniteInternalException(new OperationNotSupportedException());
    }

    /** {@inheritDoc} */
    @Override
    public int partition(BinaryRowEx keyRow) {
        return 0;
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<ClusterNode> evaluateReadOnlyRecipientNode(int partId) {
        return completedFuture(LOCAL_NODE);
    }

    /**
     * Returns dummy table index storages supplier.
     *
     * @param indexes Index storage by ID.
     */
    public static TableIndexStoragesSupplier createTableIndexStoragesSupplier(Map<Integer, TableSchemaAwareIndexStorage> indexes) {
        return new TableIndexStoragesSupplier() {
            @Override
            public Map<Integer, TableSchemaAwareIndexStorage> get() {
                return indexes;
            }

            @Override
            public void addIndexToWaitIfAbsent(int indexId) {
            }
        };
    }
}
