/*
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api.store;

import java.util.Iterator;
import java.util.NoSuchElementException;

import org.neo4j.collection.primitive.Primitive;
import org.neo4j.collection.primitive.PrimitiveIntCollections;
import org.neo4j.collection.primitive.PrimitiveIntIterator;
import org.neo4j.collection.primitive.PrimitiveIntObjectMap;
import org.neo4j.collection.primitive.PrimitiveIntObjectVisitor;
import org.neo4j.collection.primitive.PrimitiveIntSet;
import org.neo4j.collection.primitive.PrimitiveLongCollections.PrimitiveLongBaseIterator;
import org.neo4j.collection.primitive.PrimitiveLongIterator;
import org.neo4j.function.Function;
import org.neo4j.function.Predicate;
import org.neo4j.function.Predicates;
import org.neo4j.function.Supplier;
import org.neo4j.function.ToIntFunction;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Resource;
import org.neo4j.graphdb.TransactionFailureException;
import org.neo4j.kernel.api.EntityType;
import org.neo4j.kernel.api.ReadOperations;
import org.neo4j.kernel.api.constraints.UniquenessConstraint;
import org.neo4j.kernel.api.cursor.LabelCursor;
import org.neo4j.kernel.api.cursor.NodeCursor;
import org.neo4j.kernel.api.cursor.PropertyCursor;
import org.neo4j.kernel.api.cursor.RelationshipCursor;
import org.neo4j.kernel.api.exceptions.EntityNotFoundException;
import org.neo4j.kernel.api.exceptions.LabelNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.PropertyKeyIdNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.RelationshipTypeIdNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.index.IndexNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.schema.IndexBrokenKernelException;
import org.neo4j.kernel.api.exceptions.schema.SchemaRuleNotFoundException;
import org.neo4j.kernel.api.exceptions.schema.TooManyLabelsException;
import org.neo4j.kernel.api.index.IndexDescriptor;
import org.neo4j.kernel.api.index.IndexReader;
import org.neo4j.kernel.api.index.InternalIndexState;
import org.neo4j.kernel.api.properties.DefinedProperty;
import org.neo4j.kernel.api.properties.Property;
import org.neo4j.kernel.impl.api.CountsAccessor;
import org.neo4j.kernel.impl.api.DegreeVisitor;
import org.neo4j.kernel.impl.api.KernelStatement;
import org.neo4j.kernel.impl.api.RelationshipVisitor;
import org.neo4j.kernel.impl.api.index.IndexingService;
import org.neo4j.kernel.impl.core.IteratingPropertyReceiver;
import org.neo4j.kernel.impl.core.LabelTokenHolder;
import org.neo4j.kernel.impl.core.PropertyKeyTokenHolder;
import org.neo4j.kernel.impl.core.RelationshipTypeTokenHolder;
import org.neo4j.kernel.impl.core.Token;
import org.neo4j.kernel.impl.core.TokenNotFoundException;
import org.neo4j.kernel.impl.store.CommonAbstractStore;
import org.neo4j.kernel.impl.store.InvalidRecordException;
import org.neo4j.kernel.impl.store.NeoStore;
import org.neo4j.kernel.impl.store.NodeStore;
import org.neo4j.kernel.impl.store.RelationshipGroupStore;
import org.neo4j.kernel.impl.store.RelationshipStore;
import org.neo4j.kernel.impl.store.SchemaStorage;
import org.neo4j.kernel.impl.store.UnderlyingStorageException;
import org.neo4j.kernel.impl.store.UniquenessConstraintRule;
import org.neo4j.kernel.impl.store.record.IndexRule;
import org.neo4j.kernel.impl.store.record.NodeRecord;
import org.neo4j.kernel.impl.store.record.Record;
import org.neo4j.kernel.impl.store.record.RelationshipGroupRecord;
import org.neo4j.kernel.impl.store.record.RelationshipRecord;
import org.neo4j.kernel.impl.store.record.SchemaRule;
import org.neo4j.kernel.impl.transaction.state.PropertyLoader;
import org.neo4j.kernel.impl.util.Cursors;
import org.neo4j.kernel.impl.util.PrimitiveLongResourceIterator;

import static org.neo4j.collection.primitive.PrimitiveLongCollections.count;
import static org.neo4j.helpers.collection.Iterables.filter;
import static org.neo4j.helpers.collection.Iterables.map;
import static org.neo4j.helpers.collection.IteratorUtil.resourceIterator;
import static org.neo4j.kernel.impl.store.record.RecordLoad.CHECK;
import static org.neo4j.register.Registers.newDoubleLongRegister;

public class DiskLayer implements StoreReadLayer
{
    private static final Function<UniquenessConstraintRule, UniquenessConstraint> UNIQUENESS_CONSTRAINT_TO_RULE =
            new Function<UniquenessConstraintRule, UniquenessConstraint>()
            {

                @Override
                public UniquenessConstraint apply( UniquenessConstraintRule rule )
                {
                    // We can use propertyKeyId straight up here, without reading from the record, since we have
                    // verified that it has that propertyKeyId in the predicate. And since we currently only support
                    // uniqueness on single properties, there is nothing else to pass in to UniquenessConstraint.
                    return new UniquenessConstraint( rule.getLabel(), rule.getPropertyKey() );
                }
            };
    private static final Function<PropertyCursor,DefinedProperty> GET_PROPERTY = new Function<PropertyCursor, DefinedProperty>()
    {
        @Override
        public DefinedProperty apply( PropertyCursor propertyCursor )
        {
            return propertyCursor.getProperty();
        }
    };
    private static final ToIntFunction<PropertyCursor> GET_KEY_INDEX_ID = new ToIntFunction<PropertyCursor>()
    {
        @Override
        public int apply( PropertyCursor value )
        {
            return value.getKeyIndexId();
        }
    };
    private static final ToIntFunction<LabelCursor> GET_LABEL = new ToIntFunction<LabelCursor>()
    {
        @Override
        public int apply( LabelCursor cursor )
        {
            return cursor.getLabel();
        }
    };

    // These token holders should perhaps move to the cache layer.. not really any reason to have them here?
    private final PropertyKeyTokenHolder propertyKeyTokenHolder;
    private final LabelTokenHolder labelTokenHolder;
    private final RelationshipTypeTokenHolder relationshipTokenHolder;

    private final NeoStore neoStore;
    private final IndexingService indexService;
    private final NodeStore nodeStore;
    private final RelationshipGroupStore relationshipGroupStore;
    private final RelationshipStore relationshipStore;
    private final SchemaStorage schemaStorage;
    private final CountsAccessor counts;
    private final PropertyLoader propertyLoader;

    /**
     * A note on this taking Supplier<NeoStore> rather than just neo store: This is a workaround until the cache is
     * removed. Because the neostore may be restarted while the database is running, and because lazy properties keep
     * a reference to the property store, we need a way to resolve the property store on demand for properties in the
     * cache. As such, this takes a provider, and uses that provider to provide property store references when resolving
     * lazy properties.
     */
    public DiskLayer( PropertyKeyTokenHolder propertyKeyTokenHolder, LabelTokenHolder labelTokenHolder,
            RelationshipTypeTokenHolder relationshipTokenHolder, SchemaStorage schemaStorage,
            final Supplier<NeoStore> neoStoreSupplier, IndexingService indexService )
    {
        this.relationshipTokenHolder = relationshipTokenHolder;
        this.schemaStorage = schemaStorage;
        this.indexService = indexService;
        this.propertyKeyTokenHolder = propertyKeyTokenHolder;
        this.labelTokenHolder = labelTokenHolder;
        this.neoStore = neoStoreSupplier.get();
        this.nodeStore = this.neoStore.getNodeStore();
        this.relationshipStore = this.neoStore.getRelationshipStore();
        this.relationshipGroupStore = this.neoStore.getRelationshipGroupStore();
        this.counts = neoStore.getCounts();
        this.propertyLoader = new PropertyLoader( neoStore );

    }

    @Override
    public StoreStatement acquireStatement()
    {
        return neoStore.acquireStatement();
    }

    @Override
    public int labelGetOrCreateForName( String label ) throws TooManyLabelsException
    {
        try
        {
            return labelTokenHolder.getOrCreateId( label );
        }
        catch ( TransactionFailureException e )
        {
            // Temporary workaround for the property store based label
            // implementation. Actual
            // implementation should not depend on internal kernel exception
            // messages like this.
            if ( e.getCause() instanceof UnderlyingStorageException
                    && e.getCause().getMessage().equals( "Id capacity exceeded" ) )
            {
                throw new TooManyLabelsException( e );
            }
            throw e;
        }
    }

    @Override
    public int labelGetForName( String label )
    {
        return labelTokenHolder.getIdByName( label );
    }

    @Override
    public boolean nodeHasLabel( StoreStatement statement, long nodeId, int labelId ) throws EntityNotFoundException
    {
        try ( NodeCursor nodeCursor = statement.acquireSingleNodeCursor( nodeId ) )
        {
            if ( nodeCursor.next() )
            {
                try ( LabelCursor labelCursor = nodeCursor.labels() )
                {
                    return labelCursor.seek( labelId );
                }
            }
            else
            {
                throw new EntityNotFoundException( EntityType.NODE, nodeId );
            }
        }
    }

    @Override
    public PrimitiveIntIterator nodeGetLabels( StoreStatement statement, long nodeId ) throws EntityNotFoundException
    {
        try ( NodeCursor nodeCursor = statement.acquireSingleNodeCursor( nodeId ) )
        {

            if ( nodeCursor.next() )
            {
                return Cursors.intIterator(nodeCursor.labels(), GET_LABEL);
            }
            else
            {
                throw new EntityNotFoundException( EntityType.NODE, nodeId );
            }
        }
    }

    @Override
    public RelationshipIterator nodeListRelationships( StoreStatement statement,
            long nodeId,
            Direction direction )
            throws EntityNotFoundException
    {
        return nodeListRelationships( statement, nodeId, direction, null );
    }

    @Override
    public RelationshipIterator nodeListRelationships( final StoreStatement statement,
            long nodeId,
            Direction direction,
            int[] relTypes )
            throws EntityNotFoundException
    {
        try ( final NodeCursor nodeCursor = statement.acquireSingleNodeCursor( nodeId ) )
        {
            if ( nodeCursor.next() )
            {
                return new CursorRelationshipIterator(nodeCursor.relationships( direction, relTypes ));
            }
            else
            {
                throw new EntityNotFoundException( EntityType.NODE, nodeId );
            }
        }
    }

    @Override
    public int nodeGetDegree( StoreStatement statement,
            long nodeId,
            Direction direction ) throws EntityNotFoundException
    {
        NodeRecord node = nodeStore.loadRecord( nodeId, null );
        if ( node == null )
        {
            throw new EntityNotFoundException( EntityType.NODE, nodeId );
        }

        if ( node.isDense() )
        {
            long groupId = node.getNextRel();
            long count = 0;
            while ( groupId != Record.NO_NEXT_RELATIONSHIP.intValue() )
            {
                RelationshipGroupRecord group = relationshipGroupStore.getRecord( groupId );
                count += nodeDegreeByDirection( nodeId, group, direction );
                groupId = group.getNext();
            }
            return (int) count;
        }

        return count( nodeListRelationships( statement, nodeId, direction ) );
    }

    @Override
    public int nodeGetDegree( StoreStatement statement, long nodeId,
            Direction direction,
            int relType ) throws EntityNotFoundException
    {
        NodeRecord node = nodeStore.loadRecord( nodeId, null );
        if ( node == null )
        {
            throw new EntityNotFoundException( EntityType.NODE, nodeId );
        }

        if ( node.isDense() )
        {
            long groupId = node.getNextRel();
            while ( groupId != Record.NO_NEXT_RELATIONSHIP.intValue() )
            {
                RelationshipGroupRecord group = relationshipGroupStore.getRecord( groupId );
                if ( group.getType() == relType )
                {
                    return (int) nodeDegreeByDirection( nodeId, group, direction );
                }
                groupId = group.getNext();
            }
            return 0;
        }

        return count( nodeListRelationships( statement, nodeId, direction, new int[]{relType} ) );
    }

    private long nodeDegreeByDirection( long nodeId, RelationshipGroupRecord group, Direction direction )
    {
        long loopCount = countByFirstPrevPointer( nodeId, group.getFirstLoop() );
        switch ( direction )
        {
            case OUTGOING:
                return countByFirstPrevPointer( nodeId, group.getFirstOut() ) + loopCount;
            case INCOMING:
                return countByFirstPrevPointer( nodeId, group.getFirstIn() ) + loopCount;
            case BOTH:
                return countByFirstPrevPointer( nodeId, group.getFirstOut() ) +
                        countByFirstPrevPointer( nodeId, group.getFirstIn() ) + loopCount;
            default:
                throw new IllegalArgumentException( direction.name() );
        }
    }

    @Override
    public boolean nodeVisitDegrees( StoreStatement statement, final long nodeId, final DegreeVisitor visitor )
    {
        NodeRecord node = nodeStore.loadRecord( nodeId, null );
        if ( node == null )
        {
            return true;
        }

        if ( node.isDense() )
        {
            long groupId = node.getNextRel();
            while ( groupId != Record.NO_NEXT_RELATIONSHIP.intValue() )
            {
                RelationshipGroupRecord group = relationshipGroupStore.getRecord( groupId );
                long outCount = countByFirstPrevPointer( nodeId, group.getFirstOut() );
                long inCount = countByFirstPrevPointer( nodeId, group.getFirstIn() );
                long loopCount = countByFirstPrevPointer( nodeId, group.getFirstLoop() );
                visitor.visitDegree( group.getType(), (int) (outCount + loopCount), (int) (inCount + loopCount) );
                groupId = group.getNext();
            }
        }
        else
        {
            final PrimitiveIntObjectMap<int[]> degrees = Primitive.intObjectMap( 5 );
            RelationshipVisitor<RuntimeException> typeVisitor = new RelationshipVisitor<RuntimeException>()
            {
                @Override
                public void visit( long relId, int type, long startNode, long endNode ) throws RuntimeException
                {
                    int[] byType = degrees.get( type );
                    if ( byType == null )
                    {
                        degrees.put( type, byType = new int[3] );
                    }
                    byType[directionOf( nodeId, relId, startNode, endNode ).ordinal()]++;
                }
            };
            RelationshipIterator relationships;
            try
            {
                relationships = nodeListRelationships( statement, nodeId, Direction.BOTH );
                while ( relationships.hasNext() )
                {
                    relationships.relationshipVisit( relationships.next(), typeVisitor );
                }

                degrees.visitEntries( new PrimitiveIntObjectVisitor<int[], RuntimeException>()
                {
                    @Override
                    public boolean visited( int type, int[] degrees /*out,in,loop*/ ) throws RuntimeException
                    {
                        visitor.visitDegree( type, degrees[0] + degrees[2], degrees[1] + degrees[2] );
                        return false;
                    }
                } );
            }
            catch ( EntityNotFoundException e )
            {
                // OK?
            }
        }
        return false;
    }

    private Direction directionOf( long nodeId, long relationshipId, long startNode, long endNode )
    {
        if ( startNode == nodeId )
        {
            return endNode == nodeId ? Direction.BOTH : Direction.OUTGOING;
        }
        if ( endNode == nodeId )
        {
            return Direction.INCOMING;
        }
        throw new InvalidRecordException( "Node " + nodeId + " neither start nor end node of relationship " +
                relationshipId + " with startNode:" + startNode + " and endNode:" + endNode );
    }

    private long countByFirstPrevPointer( long nodeId, long relationshipId )
    {
        if ( relationshipId == Record.NO_NEXT_RELATIONSHIP.intValue() )
        {
            return 0;
        }
        RelationshipRecord record = relationshipStore.getRecord( relationshipId );
        if ( record.getFirstNode() == nodeId )
        {
            return record.getFirstPrevRel();
        }
        if ( record.getSecondNode() == nodeId )
        {
            return record.getSecondPrevRel();
        }
        throw new InvalidRecordException( "Node " + nodeId + " neither start nor end node of " + record );
    }

    @Override
    public PrimitiveIntIterator nodeGetRelationshipTypes( StoreStatement statement,
            long nodeId ) throws EntityNotFoundException
    {
        final NodeRecord node = nodeStore.loadRecord( nodeId, null );
        if ( node == null )
        {
            throw new EntityNotFoundException( EntityType.NODE, nodeId );
        }

        if ( node.isDense() )
        {
            return new PrimitiveIntCollections.PrimitiveIntBaseIterator()
            {
                private long groupId = node.getNextRel();

                @Override
                protected boolean fetchNext()
                {
                    if ( groupId == Record.NO_NEXT_RELATIONSHIP.intValue() )
                    {
                        return false;
                    }

                    RelationshipGroupRecord group = relationshipGroupStore.getRecord( groupId );
                    try
                    {
                        return next( group.getType() );
                    }
                    finally
                    {
                        groupId = group.getNext();
                    }
                }
            };
        }

        final PrimitiveIntSet types = Primitive.intSet( 5 );
        RelationshipVisitor<RuntimeException> visitor = new RelationshipVisitor<RuntimeException>()
        {
            @Override
            public void visit( long relId, int type, long startNode, long endNode ) throws RuntimeException
            {
                types.add( type );
            }
        };
        RelationshipIterator relationships = nodeListRelationships( statement, nodeId, Direction.BOTH );
        while ( relationships.hasNext() )
        {
            relationships.relationshipVisit( relationships.next(), visitor );
        }
        return types.iterator();
    }

    @Override
    public String labelGetName( int labelId ) throws LabelNotFoundKernelException
    {
        try
        {
            return labelTokenHolder.getTokenById( labelId ).name();
        }
        catch ( TokenNotFoundException e )
        {
            throw new LabelNotFoundKernelException( "Label by id " + labelId, e );
        }
    }

    @Override
    public PrimitiveLongIterator nodesGetForLabel( KernelStatement state, int labelId )
    {
        return state.getLabelScanReader().nodesWithLabel( labelId );
    }

    @Override
    public PrimitiveLongResourceIterator nodesGetFromIndexLookup( KernelStatement state, IndexDescriptor index,
            Object value ) throws IndexNotFoundKernelException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public PrimitiveLongResourceIterator nodesGetFromIndexByPrefixSearch( KernelStatement state,
            IndexDescriptor index,
            String prefix )
            throws IndexNotFoundKernelException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public PrimitiveLongResourceIterator nodesGetFromIndexScan( KernelStatement state, IndexDescriptor index ) throws
            IndexNotFoundKernelException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public IndexDescriptor indexesGetForLabelAndPropertyKey( int labelId, int propertyKey )
    {
        return descriptor( schemaStorage.indexRule( labelId, propertyKey ) );
    }

    private static IndexDescriptor descriptor( IndexRule ruleRecord )
    {
        return new IndexDescriptor( ruleRecord.getLabel(), ruleRecord.getPropertyKey() );
    }

    @Override
    public Iterator<IndexDescriptor> indexesGetForLabel( int labelId )
    {
        return getIndexDescriptorsFor( indexRules( labelId ) );
    }

    @Override
    public Iterator<IndexDescriptor> indexesGetAll()
    {
        return getIndexDescriptorsFor( INDEX_RULES );
    }

    @Override
    public Iterator<IndexDescriptor> uniqueIndexesGetForLabel( int labelId )
    {
        return getIndexDescriptorsFor( constraintIndexRules( labelId ) );
    }

    @Override
    public Iterator<IndexDescriptor> uniqueIndexesGetAll()
    {
        return getIndexDescriptorsFor( CONSTRAINT_INDEX_RULES );
    }

    private static Predicate<SchemaRule> indexRules( final int labelId )
    {
        return new Predicate<SchemaRule>()
        {

            @Override
            public boolean test( SchemaRule rule )
            {
                return rule.getLabel() == labelId && rule.getKind() == SchemaRule.Kind.INDEX_RULE;
            }
        };
    }

    private static Predicate<SchemaRule> constraintIndexRules( final int labelId )
    {
        return new Predicate<SchemaRule>()
        {

            @Override
            public boolean test( SchemaRule rule )
            {
                return rule.getLabel() == labelId && rule.getKind() == SchemaRule.Kind.CONSTRAINT_INDEX_RULE;
            }
        };
    }

    private static final Predicate<SchemaRule> INDEX_RULES = new Predicate<SchemaRule>()
    {

        @Override
        public boolean test( SchemaRule rule )
        {
            return rule.getKind() == SchemaRule.Kind.INDEX_RULE;
        }
    }, CONSTRAINT_INDEX_RULES = new Predicate<SchemaRule>()
    {

        @Override
        public boolean test( SchemaRule rule )
        {
            return rule.getKind() == SchemaRule.Kind.CONSTRAINT_INDEX_RULE;
        }
    };

    private Iterator<IndexDescriptor> getIndexDescriptorsFor( Predicate<SchemaRule> filter )
    {
        Iterator<SchemaRule> filtered = filter( filter, neoStore.getSchemaStore().loadAllSchemaRules() );

        return map( new Function<SchemaRule, IndexDescriptor>()
        {

            @Override
            public IndexDescriptor apply( SchemaRule from )
            {
                return descriptor( (IndexRule) from );
            }
        }, filtered );
    }

    @Override
    public boolean nodeExists( long nodeId )
    {
        return nodeStore.inUse( nodeId );
    }

    @Override
    public boolean relationshipExists( long relationshipId )
    {
        return relationshipStore.inUse( relationshipId );
    }

    @Override
    public Long indexGetOwningUniquenessConstraintId( IndexDescriptor index )
            throws SchemaRuleNotFoundException
    {
        return schemaStorage.indexRule( index.getLabelId(), index.getPropertyKeyId() ).getOwningConstraint();
    }

    @Override
    public IndexRule indexRule( IndexDescriptor index, SchemaStorage.IndexRuleKind kind )
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public long indexGetCommittedId( IndexDescriptor index, SchemaStorage.IndexRuleKind kind )
            throws SchemaRuleNotFoundException
    {
        return schemaStorage.indexRule( index.getLabelId(), index.getPropertyKeyId() ).getId();
    }

    @Override
    public InternalIndexState indexGetState( IndexDescriptor descriptor )
            throws IndexNotFoundKernelException
    {
        return indexService.getIndexProxy( indexId( descriptor ) ).getState();
    }

    @Override
    public long indexSize( IndexDescriptor descriptor ) throws IndexNotFoundKernelException
    {
        return indexService.indexSize( indexId( descriptor ) );
    }

    @Override
    public double indexUniqueValuesPercentage( IndexDescriptor descriptor ) throws IndexNotFoundKernelException
    {
        return indexService.indexUniqueValuesPercentage( indexId( descriptor ) );
    }

    @Override
    public String indexGetFailure( IndexDescriptor descriptor ) throws IndexNotFoundKernelException
    {
        return indexService.getIndexProxy( indexId( descriptor ) ).getPopulationFailure().asString();
    }

    private long indexId( IndexDescriptor descriptor )
    {
        return schemaStorage.indexRule( descriptor.getLabelId(), descriptor.getPropertyKeyId() ).getId();
    }

    @Override
    public Iterator<UniquenessConstraint> constraintsGetForLabelAndPropertyKey( int labelId, final int propertyKeyId )
    {
        return schemaStorage.schemaRules( UNIQUENESS_CONSTRAINT_TO_RULE, UniquenessConstraintRule.class,
                labelId, new Predicate<UniquenessConstraintRule>()
                {
                    @Override
                    public boolean test( UniquenessConstraintRule rule )
                    {
                        return rule.containsPropertyKeyId( propertyKeyId );
                    }
                } );
    }

    @Override
    public Iterator<UniquenessConstraint> constraintsGetForLabel( int labelId )
    {
        return schemaStorage.schemaRules( UNIQUENESS_CONSTRAINT_TO_RULE, UniquenessConstraintRule.class,
                labelId, Predicates.<UniquenessConstraintRule>alwaysTrue() );
    }

    @Override
    public Iterator<UniquenessConstraint> constraintsGetAll()
    {
        return schemaStorage.schemaRules( UNIQUENESS_CONSTRAINT_TO_RULE, SchemaRule.Kind.UNIQUENESS_CONSTRAINT,
                Predicates.<UniquenessConstraintRule>alwaysTrue() );
    }

    @Override
    public PrimitiveLongResourceIterator nodeGetUniqueFromIndexLookup( KernelStatement state, IndexDescriptor index,
            Object value ) throws IndexNotFoundKernelException, IndexBrokenKernelException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public int propertyKeyGetOrCreateForName( String propertyKey )
    {
        return propertyKeyTokenHolder.getOrCreateId( propertyKey );
    }

    @Override
    public int propertyKeyGetForName( String propertyKey )
    {
        return propertyKeyTokenHolder.getIdByName( propertyKey );
    }

    @Override
    public String propertyKeyGetName( int propertyKeyId )
            throws PropertyKeyIdNotFoundKernelException
    {
        try
        {
            return propertyKeyTokenHolder.getTokenById( propertyKeyId ).name();
        }
        catch ( TokenNotFoundException e )
        {
            throw new PropertyKeyIdNotFoundKernelException( propertyKeyId, e );
        }
    }

    @Override
    public PrimitiveIntIterator relationshipGetPropertyKeys( final StoreStatement statement,
            long relationshipId ) throws EntityNotFoundException
    {

        try ( RelationshipCursor relCursor = statement.acquireSingleRelationshipCursor( relationshipId ) )
        {
            if ( !relCursor.next() )
            {
                throw new EntityNotFoundException( EntityType.RELATIONSHIP, relationshipId );
            }

            return Cursors.intIterator(relCursor.properties(), GET_KEY_INDEX_ID);
        }
    }

    @Override
    public Property relationshipGetProperty( StoreStatement statement,
            long relationshipId,
            int propertyKeyId ) throws EntityNotFoundException
    {
        try ( RelationshipCursor storeRelationshipCursor = statement.acquireSingleRelationshipCursor( relationshipId ) )
        {
            if ( !storeRelationshipCursor.next() )
            {
                throw new EntityNotFoundException( EntityType.RELATIONSHIP, relationshipId );
            }

            try ( PropertyCursor cursor = storeRelationshipCursor.properties() )
            {
                if ( cursor.seek( propertyKeyId ) )
                {
                    return cursor.getProperty();
                }
            }

            return Property.noRelationshipProperty( relationshipId, propertyKeyId );
        }
    }

    @Override
    public PrimitiveIntIterator nodeGetPropertyKeys( final StoreStatement statement,
            long nodeId ) throws EntityNotFoundException
    {
        try ( NodeCursor nodeCursor = statement.acquireSingleNodeCursor( nodeId ) )
        {
            if ( !nodeCursor.next() )
            {
                throw new EntityNotFoundException( EntityType.NODE, nodeId );
            }

            return Cursors.intIterator( nodeCursor.properties(), GET_KEY_INDEX_ID );
        }
    }

    @Override
    public Property nodeGetProperty( StoreStatement statement,
            long nodeId,
            int propertyKeyId ) throws EntityNotFoundException
    {
        try ( NodeCursor storeNodeCursor = statement.acquireSingleNodeCursor( nodeId ) )
        {
            if ( !storeNodeCursor.next() )
            {
                throw new EntityNotFoundException( EntityType.NODE, nodeId );
            }

            try ( PropertyCursor cursor = storeNodeCursor.properties() )
            {
                if ( cursor.seek( propertyKeyId ) )
                {
                    return cursor.getProperty();
                }
            }

            return Property.noNodeProperty( nodeId, propertyKeyId );
        }
    }

    @Override
    public Iterator<DefinedProperty> nodeGetAllProperties( StoreStatement statement,
            long nodeId )
            throws EntityNotFoundException
    {
        try ( NodeCursor storeNodeCursor = statement.acquireSingleNodeCursor( nodeId ) )
        {
            if ( !storeNodeCursor.next() )
            {
                throw new EntityNotFoundException( EntityType.NODE, nodeId );
            }

            return Cursors.iterator( storeNodeCursor.properties(), GET_PROPERTY );
        }
    }

    @Override
    public Iterator<DefinedProperty> relationshipGetAllProperties( StoreStatement statement, final long relationshipId )
            throws EntityNotFoundException
    {
        try ( RelationshipCursor storeRelationshipCursor = statement.acquireSingleRelationshipCursor( relationshipId ) )
        {
            if ( !storeRelationshipCursor.next() )
            {
                throw new EntityNotFoundException( EntityType.RELATIONSHIP, relationshipId );
            }

            return Cursors.iterator( storeRelationshipCursor.properties(), GET_PROPERTY );
        }
    }

    @Override
    public PrimitiveIntIterator graphGetPropertyKeys( KernelStatement state )
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Property graphGetProperty( int propertyKeyId )
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterator<DefinedProperty> graphGetAllProperties()
    {
        return propertyLoader.graphLoadProperties( new IteratingPropertyReceiver() );
    }

    public PrimitiveLongResourceIterator nodeGetUniqueFromIndexLookup( KernelStatement state,
            long indexId, Object value )
            throws IndexNotFoundKernelException
    {
        /* Here we have an intricate scenario where we need to return the PrimitiveLongIterator
         * since subsequent filtering will happen outside, but at the same time have the ability to
         * close the IndexReader when done iterating over the lookup result. This is because we get
         * a fresh reader that isn't associated with the current transaction and hence will not be
         * automatically closed. */
        IndexReader reader = state.getFreshIndexReader( indexId );
        return resourceIterator( reader.lookup( value ), reader );
    }

    public PrimitiveLongResourceIterator nodesGetFromIndexLookup( KernelStatement state, long index, Object value )
            throws IndexNotFoundKernelException
    {
        IndexReader reader = state.getIndexReader( index );
        return resourceIterator( reader.lookup( value ), reader );
    }

    public PrimitiveLongResourceIterator nodesGetFromIndexByPrefixSearch( KernelStatement state, long index, String prefix )
            throws IndexNotFoundKernelException
    {
        IndexReader reader = state.getIndexReader( index );
        return resourceIterator( reader.lookupByPrefixSearch( prefix ), reader );
    }

    public PrimitiveLongResourceIterator nodesGetFromIndexScan( KernelStatement state, long index )
            throws IndexNotFoundKernelException
    {
        IndexReader reader = state.getIndexReader( index );
        return resourceIterator( reader.scan(), reader );
    }

    @Override
    public Iterator<Token> propertyKeyGetAllTokens()
    {
        return propertyKeyTokenHolder.getAllTokens().iterator();
    }

    @Override
    public Iterator<Token> labelsGetAllTokens()
    {
        return labelTokenHolder.getAllTokens().iterator();
    }

    @Override
    public int relationshipTypeGetForName( String relationshipTypeName )
    {
        return relationshipTokenHolder.getIdByName( relationshipTypeName );
    }

    @Override
    public String relationshipTypeGetName( int relationshipTypeId ) throws RelationshipTypeIdNotFoundKernelException
    {
        try
        {
            return ((Token) relationshipTokenHolder.getTokenById( relationshipTypeId )).name();
        }
        catch ( TokenNotFoundException e )
        {
            throw new RelationshipTypeIdNotFoundKernelException( relationshipTypeId, e );
        }
    }

    @Override
    public int relationshipTypeGetOrCreateForName( String relationshipTypeName )
    {
        return relationshipTokenHolder.getOrCreateId( relationshipTypeName );
    }

    @Override
    public <EXCEPTION extends Exception> void relationshipVisit( long relationshipId,
            RelationshipVisitor<EXCEPTION> relationshipVisitor ) throws EntityNotFoundException, EXCEPTION
    {
        // TODO Please don't create a record for this, it's ridiculous
        RelationshipRecord record;
        try
        {
            record = relationshipStore.getRecord( relationshipId );
        }
        catch ( InvalidRecordException e )
        {
            throw new EntityNotFoundException( EntityType.RELATIONSHIP, relationshipId );
        }
        relationshipVisitor.visit( relationshipId, record.getType(), record.getFirstNode(), record.getSecondNode() );
    }

    @Override
    public long highestNodeIdInUse()
    {
        return nodeStore.getHighestPossibleIdInUse();
    }

    @Override
    public PrimitiveLongIterator nodesGetAll()
    {
        return new PrimitiveLongBaseIterator()
        {
            private final NodeStore store = neoStore.getNodeStore();
            private long highId = store.getHighestPossibleIdInUse();
            private long currentId;
            private final NodeRecord reusableNodeRecord = new NodeRecord( -1 ); // reused

            @Override
            protected boolean fetchNext()
            {
                while ( true )
                {   // This outer loop is for checking if highId has changed since we started.
                    while ( currentId <= highId )
                    {
                        try
                        {
                            NodeRecord record = store.loadRecord( currentId, reusableNodeRecord );
                            if ( record != null && record.inUse() )
                            {
                                return next( record.getId() );
                            }
                        }
                        finally
                        {
                            currentId++;
                        }
                    }

                    long newHighId = store.getHighestPossibleIdInUse();
                    if ( newHighId > highId )
                    {
                        highId = newHighId;
                    }
                    else
                    {
                        break;
                    }
                }
                return false;
            }
        };
    }

    @Override
    public NodeCursor nodesGetAllCursor( StoreStatement statement )
    {
        return statement.acquireIteratorNodeCursor().init( new AllStoreIdIterator( neoStore.getNodeStore() ) );
    }

    @Override
    public RelationshipIterator relationshipsGetAll()
    {
        return new RelationshipIterator.BaseIterator()
        {
            private final RelationshipStore store = neoStore.getRelationshipStore();
            private long highId = store.getHighestPossibleIdInUse();
            private long currentId;
            private final RelationshipRecord reusableRecord = new RelationshipRecord( -1 ); // reused

            @Override
            protected boolean fetchNext()
            {
                while ( true )
                {   // This outer loop is for checking if highId has changed since we started.
                    while ( currentId <= highId )
                    {
                        try
                        {
                            if ( store.fillRecord( currentId, reusableRecord, CHECK ) && reusableRecord.inUse() )
                            {
                                return next( reusableRecord.getId() );
                            }
                        }
                        finally
                        {
                            currentId++;
                        }
                    }

                    long newHighId = store.getHighestPossibleIdInUse();
                    if ( newHighId > highId )
                    {
                        highId = newHighId;
                    }
                    else
                    {
                        break;
                    }
                }
                return false;
            }

            @Override
            public <EXCEPTION extends Exception> boolean relationshipVisit( long relationshipId,
                    RelationshipVisitor<EXCEPTION> visitor ) throws EXCEPTION
            {
                visitor.visit( relationshipId, reusableRecord.getType(),
                        reusableRecord.getFirstNode(), reusableRecord.getSecondNode() );
                return false;
            }
        };
    }

    @Override
    public RelationshipCursor relationshipsGetAllCursor( StoreStatement storeStatement )
    {
        return storeStatement.acquireIteratorRelationshipCursor().init(
                new AllStoreIdIterator( neoStore.getRelationshipStore() ) );
    }

    @Override
    public long reserveNode()
    {
        return nodeStore.nextId();
    }

    @Override
    public long reserveRelationship()
    {
        return relationshipStore.nextId();
    }

    @Override
    public void releaseNode( long id )
    {
        nodeStore.freeId( id );
    }

    @Override
    public void releaseRelationship( long id )
    {
        relationshipStore.freeId( id );
    }

    @Override
    public long countsForNode( int labelId )
    {
        return counts.nodeCount( labelId, newDoubleLongRegister() ).readSecond();
    }

    @Override
    public long countsForRelationship( int startLabelId, int typeId, int endLabelId )
    {
        if ( !(startLabelId == ReadOperations.ANY_LABEL || endLabelId == ReadOperations.ANY_LABEL) )
        {
            throw new UnsupportedOperationException( "not implemented" );
        }
        return counts.relationshipCount( startLabelId, typeId, endLabelId, newDoubleLongRegister() ).readSecond();
    }

    private class AllStoreIdIterator extends PrimitiveLongBaseIterator
    {
        private final CommonAbstractStore store;
        private long highId;
        private long currentId;

        public AllStoreIdIterator( CommonAbstractStore store )
        {
            this.store = store;
            highId = store.getHighestPossibleIdInUse();
        }

        @Override
        protected boolean fetchNext()
        {
            while ( true )
            {   // This outer loop is for checking if highId has changed since we started.
                if ( currentId <= highId )
                {
                    try
                    {
                        return next( currentId );
                    }
                    finally
                    {
                        currentId++;
                    }
                }

                long newHighId = store.getHighestPossibleIdInUse();
                if ( newHighId > highId )
                {
                    highId = newHighId;
                }
                else
                {
                    break;
                }
            }
            return false;
        }
    }

    private static class CursorRelationshipIterator implements RelationshipIterator, Resource
    {
        private RelationshipCursor cursor;
        private boolean hasNext;

        private long id;
        private int type;
        private long startNode;
        private long endNode;

        public CursorRelationshipIterator( RelationshipCursor resourceCursor )
        {
            cursor = resourceCursor;
            hasNext = nextCursor();
        }

        private boolean nextCursor()
        {
            if (cursor != null)
            {
                boolean hasNext = cursor.next();
                if ( !hasNext )
                {
                    close();
                }
                return hasNext;
            } else
            {
                return false;
            }
        }

        @Override
        public boolean hasNext()
        {
            return hasNext;
        }

        @Override
        public long next()
        {
            if ( hasNext )
            {
                try
                {
                    id = cursor.getId();
                    type = cursor.getType();
                    startNode = cursor.getStartNode();
                    endNode = cursor.getEndNode();

                    return id;
                }
                finally
                {
                    hasNext = nextCursor();
                }
            }
            else
            {
                throw new NoSuchElementException();
            }
        }

        @Override
        public <EXCEPTION extends Exception> boolean relationshipVisit( long relationshipId,
                RelationshipVisitor<EXCEPTION> visitor ) throws EXCEPTION
        {
            visitor.visit( id, type, startNode, endNode );
            return false;
        }


        @Override
        public void close()
        {
            if (cursor != null )
            {
                cursor.close();
                cursor = null;
            }
        }
    }
}
