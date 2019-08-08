package org.hisp.dhis.analytics.table;

/*
 * Copyright (c) 2004-2019, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.hisp.dhis.analytics.*;
import org.hisp.dhis.analytics.partition.PartitionManager;
import org.hisp.dhis.analytics.util.AnalyticsTableAsserter;
import org.hisp.dhis.category.Category;
import org.hisp.dhis.category.CategoryCombo;
import org.hisp.dhis.category.CategoryOptionGroupSet;
import org.hisp.dhis.category.CategoryService;
import org.hisp.dhis.common.IdentifiableObjectManager;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.dataapproval.DataApprovalLevelService;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.jdbc.StatementBuilder;
import org.hisp.dhis.jdbc.statementbuilder.PostgreSQLStatementBuilder;
import org.hisp.dhis.organisationunit.OrganisationUnitGroupSet;
import org.hisp.dhis.organisationunit.OrganisationUnitLevel;
import org.hisp.dhis.organisationunit.OrganisationUnitService;
import org.hisp.dhis.period.PeriodType;
import org.hisp.dhis.program.Program;
import org.hisp.dhis.program.ProgramStage;
import org.hisp.dhis.program.ProgramTrackedEntityAttribute;
import org.hisp.dhis.random.BeanRandomizer;
import org.hisp.dhis.resourcetable.ResourceTableService;
import org.hisp.dhis.setting.SystemSettingManager;
import org.hisp.dhis.system.database.DatabaseInfo;
import org.hisp.dhis.trackedentity.TrackedEntityAttribute;
import org.hisp.dhis.util.DateUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hisp.dhis.DhisConvenienceTest.*;
import static org.hisp.dhis.analytics.ColumnDataType.*;
import static org.hisp.dhis.analytics.ColumnDataType.TEXT;
import static org.junit.Assert.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Luciano Fiandesio
 */
public class JdbcEventAnalyticsTableManagerTest
{
    @Mock
    private IdentifiableObjectManager idObjectManager;

    @Mock
    private OrganisationUnitService organisationUnitService;

    @Mock
    private CategoryService categoryService;

    @Mock
    private SystemSettingManager systemSettingManager;

    @Mock
    private DataApprovalLevelService dataApprovalLevelService;

    @Mock
    private ResourceTableService resourceTableService;

    @Mock
    private AnalyticsTableHookService tableHookService;

    private StatementBuilder statementBuilder;

    @Mock
    private PartitionManager partitionManager;

    @Mock
    private DatabaseInfo databaseInfo;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Rule
    public MockitoRule mockitoRule = MockitoJUnit.rule();

    private JdbcEventAnalyticsTableManager subject;

    private BeanRandomizer rnd = new BeanRandomizer();

    private final static String TABLE_PREFIX = "analytics_event_";

    private final static String FROM_CLAUSE = "from programstageinstance where programstageinstanceid=psi.programstageinstanceid";

    private List<AnalyticsTableColumn> periodColumns = PeriodType.getAvailablePeriodTypes().stream().map( pt -> {
        String column = quote( pt.getName().toLowerCase() );
        return new AnalyticsTableColumn( column, TEXT, "dps" + "." + column );
    } ).collect( Collectors.toList() );

    @Before
    public void setUp()
    {
        statementBuilder = new PostgreSQLStatementBuilder();
        subject = new JdbcEventAnalyticsTableManager( idObjectManager, organisationUnitService, categoryService, systemSettingManager,
            dataApprovalLevelService, resourceTableService, tableHookService, statementBuilder, partitionManager, databaseInfo, jdbcTemplate );
        when( jdbcTemplate.queryForList(
            "select distinct(extract(year from psi.executiondate)) from programstageinstance psi inner join programinstance pi on psi.programinstanceid = pi.programinstanceid where pi.programid = 0 and psi.executiondate is not null and psi.deleted is false and psi.executiondate >= '2018-01-01'",
            Integer.class ) ).thenReturn( Lists.newArrayList( 2018, 2019 ) );
    }

    @Test
    public void verifyTableType()
    {
        assertThat( subject.getAnalyticsTableType(), is( AnalyticsTableType.EVENT ) );
    }

    @Test
    public void verifyGetTableWithCategoryCombo()
    {
        Program program = createProgram( 'A' );

        Category categoryA = createCategory( 'A' );
        categoryA.setCreated( getDate( 2019, 12, 3 ) );
        Category categoryB = createCategory( 'B' );
        categoryA.setCreated( getDate( 2018, 8, 5 ) );
        CategoryCombo categoryCombo = createCategoryCombo( 'B', categoryA, categoryB );

        addCategoryCombo( program, categoryCombo );


        when( idObjectManager.getAllNoAcl( Program.class ) ).thenReturn( Lists.newArrayList( program ) );

        AnalyticsTableUpdateParams params = AnalyticsTableUpdateParams.newBuilder().withLastYears( 2 ).build();

        when( jdbcTemplate.queryForList( getYearsQuery( program, params ), Integer.class ) )
            .thenReturn( Lists.newArrayList( 2018, 2019 ) );

        List<AnalyticsTable> tables = subject.getAnalyticsTables( params );

        assertThat( tables, hasSize( 1 ) );

        new AnalyticsTableAsserter.Builder( tables.get( 0 ) ).withTableType( AnalyticsTableType.EVENT )
            .withTableName( TABLE_PREFIX + program.getUid().toLowerCase() )
            .withColumnSize( 41 ).withDefaultColumns( subject.getFixedColumns() )
            .addColumns(periodColumns)
            .addColumn( categoryA.getUid(), CHARACTER_11, "acs.", categoryA.getCreated() )
            .addColumn( categoryB.getUid(), CHARACTER_11, "acs.", categoryB.getCreated() ).build()
            .verify();
    }

    @Test
    public void verifyGetTableWithDataElements()
    {
        when( databaseInfo.isSpatialSupport() ).thenReturn( true );
        Program program = createProgram( 'A' );

        DataElement d1 = createDataElement('Z', ValueType.TEXT, AggregationType.SUM);
        DataElement d2 = createDataElement('P', ValueType.PERCENTAGE, AggregationType.SUM);
        DataElement d3 = createDataElement('Y', ValueType.BOOLEAN, AggregationType.NONE);
        DataElement d4 = createDataElement('W', ValueType.DATE, AggregationType.LAST);
        DataElement d5 = createDataElement('G', ValueType.ORGANISATION_UNIT, AggregationType.NONE);
        DataElement d6 = createDataElement('H', ValueType.INTEGER, AggregationType.SUM);
        DataElement d7 = createDataElement('U', ValueType.COORDINATE, AggregationType.NONE);

        ProgramStage ps1 = createProgramStage( 'A', Sets.newHashSet( d1, d2, d3, d4, d5, d6, d7 ) );

        program.setProgramStages( Sets.newHashSet( ps1 ) );

        when( idObjectManager.getAllNoAcl( Program.class ) ).thenReturn( Lists.newArrayList( program ) );

        String aliasD1 = "(select eventdatavalues #>> '{%s, value}' " + FROM_CLAUSE + " ) as \"%s\"";
        String aliasD2 = "(select cast(eventdatavalues #>> '{%s, value}' as " + statementBuilder.getDoubleColumnType() + ") "+ FROM_CLAUSE +"  and eventdatavalues #>> '{%s,value}' " + statementBuilder.getRegexpMatch() + " '^(-?[0-9]+)(\\.[0-9]+)?$') as \"%s\"";
        String aliasD3 = "(select case when eventdatavalues #>> '{%s, value}' = 'true' then 1 when eventdatavalues #>> '{%s, value}' = 'false' then 0 else null end " + FROM_CLAUSE + " ) as \"%s\"";
        String aliasD4 = "(select cast(eventdatavalues #>> '{%s, value}' as timestamp) " + FROM_CLAUSE + "  and eventdatavalues #>> '{%s,value}' " + statementBuilder.getRegexpMatch() + " '^\\d{4}-\\d{2}-\\d{2}(\\s|T)?((\\d{2}:)(\\d{2}:)?(\\d{2}))?$') as \"%s\"";
        String aliasD5 = "(select ou.name from organisationunit ou where ou.uid = " + "(select eventdatavalues #>> '{"
            + d5.getUid() + ", value}' " + FROM_CLAUSE + " )) as \"" + d5.getUid() + "\"";
        String aliasD6 = "(select cast(eventdatavalues #>> '{%s, value}' as bigint) " + FROM_CLAUSE + "  and eventdatavalues #>> '{%s,value}' " + statementBuilder.getRegexpMatch() + " '^(-?[0-9]+)(\\.[0-9]+)?$') as \"%s\"";
        String aliasD7 = "(select ST_GeomFromGeoJSON('{\"type\":\"Point\", \"coordinates\":' || (eventdatavalues #>> '{%s, value}') || ', \"crs\":{\"type\":\"name\", \"properties\":{\"name\":\"EPSG:4326\"}}}') from programstageinstance where programstageinstanceid=psi.programstageinstanceid ) as \"%s\"";
        String aliasD5_geo = "(select ou.geometry from organisationunit ou where ou.uid = (select eventdatavalues #>> '{"
        + d5.getUid() +", value}' " + FROM_CLAUSE + " )) as \"" +  d5.getUid() + "\"";

        AnalyticsTableUpdateParams params = AnalyticsTableUpdateParams.newBuilder().withLastYears( 2 ).build();

        when( jdbcTemplate.queryForList( getYearsQuery( program, params ), Integer.class ) )
            .thenReturn( Lists.newArrayList( 2018, 2019 ) );

        List<AnalyticsTable> tables = subject.getAnalyticsTables( params );

        assertThat( tables, hasSize( 1 ) );

        new AnalyticsTableAsserter.Builder( tables.get( 0 ) )
            .withTableName( TABLE_PREFIX + program.getUid().toLowerCase() )
            .withTableType( AnalyticsTableType.EVENT )
            .withColumnSize( 47 )
            .addColumns( periodColumns )
            .addColumn( d1.getUid(), TEXT, toAlias( aliasD1, d1.getUid() ) )  // ValueType.TEXT
            .addColumn( d2.getUid(), DOUBLE, toAlias( aliasD2, d2.getUid() ) ) // ValueType.PERCENTAGE
            .addColumn( d3.getUid(), INTEGER, toAlias( aliasD3, d3.getUid() ) ) // ValueType.BOOLEAN
            .addColumn( d4.getUid(), TIMESTAMP, toAlias( aliasD4, d4.getUid() ) ) // ValueType.DATE
            .addColumn( d5.getUid(), TEXT, toAlias( aliasD5, d5.getUid() ) ) // ValueType.ORGANISATION_UNIT
            .addColumn( d6.getUid(), BIGINT, toAlias( aliasD6, d6.getUid() ) ) // ValueType.INTEGER
            .addColumn( d7.getUid(), GEOMETRY_POINT, toAlias( aliasD7, d7.getUid() ) ) // ValueType.COORDINATES
            .addColumnUnquoted( d5.getUid() + "_geom" , GEOMETRY_POINT, toAlias(aliasD5_geo, d5.getUid()) ) // element d5 also creates a Geo column
            .withDefaultColumns( subject.getFixedColumns() )
            .build().verify();
    }

    @Test
    public void verifyGetTableWithTrackedEntityAttribute()
    {
        when( databaseInfo.isSpatialSupport() ).thenReturn( true );
        Program program = createProgram( 'A' );

        TrackedEntityAttribute tea1 = rnd.randomObject(TrackedEntityAttribute.class);
        tea1.setValueType(ValueType.ORGANISATION_UNIT);

        ProgramTrackedEntityAttribute tea = new ProgramTrackedEntityAttribute(program, tea1);
        
        program.setProgramAttributes( Collections.singletonList( tea ) );

        DataElement d1 = createDataElement('Z', ValueType.TEXT, AggregationType.SUM);

        ProgramStage ps1 = createProgramStage( 'A', Sets.newHashSet( d1 ) );

        program.setProgramStages( Sets.newHashSet( ps1 ) );

        when( idObjectManager.getAllNoAcl( Program.class ) ).thenReturn( Lists.newArrayList( program ) );

        String aliasD1 = "(select eventdatavalues #>> '{%s, value}' " + FROM_CLAUSE + " ) as \"%s\"";
        String aliasTea1 = "(select %s from organisationunit ou where ou.uid = (select value from " +
                "trackedentityattributevalue where trackedentityinstanceid=pi.trackedentityinstanceid and " +
                "trackedentityattributeid=%d)) as \"%s\"";

        AnalyticsTableUpdateParams params = AnalyticsTableUpdateParams.newBuilder().withLastYears( 2 ).build();
        when( jdbcTemplate.queryForList( getYearsQuery( program, params ), Integer.class ) )
            .thenReturn( Lists.newArrayList( 2018, 2019 ) );

        List<AnalyticsTable> tables = subject.getAnalyticsTables( params );

        assertThat( tables, hasSize( 1 ) );

        new AnalyticsTableAsserter.Builder( tables.get( 0 ) )
            .withTableName( TABLE_PREFIX + program.getUid().toLowerCase() )
            .withTableType( AnalyticsTableType.EVENT )
            .withColumnSize( 42 )
            .addColumns( periodColumns )
            .addColumn( d1.getUid(), TEXT, toAlias( aliasD1, d1.getUid() ) )  // ValueType.TEXT
            .addColumn( tea1.getUid(), TEXT, String.format(aliasTea1, "ou.name", tea1.getId(), tea1.getUid()) )  // ValueType.ORGANISATION_UNIT
            // Second Geometry column created from the OU column above
            .addColumnUnquoted(  tea1.getUid() + "_geom", GEOMETRY, String.format(aliasTea1, "ou.geometry", tea1.getId(), tea1.getUid()) )
            .withDefaultColumns( subject.getFixedColumns() )
            .build().verify();
    }

    @Test
    public void verifyDataElementTypeOrgUnitFetchesOuNameWhenPopulatingEventAnalyticsTable()
    {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when( databaseInfo.isSpatialSupport() ).thenReturn( true );
        Program program = createProgram( 'A' );

        DataElement d5 = createDataElement('G', ValueType.ORGANISATION_UNIT, AggregationType.NONE);

        ProgramStage ps1 = createProgramStage( 'A', Sets.newHashSet( d5 ) );

        program.setProgramStages( Sets.newHashSet( ps1 ) );

        when( idObjectManager.getAllNoAcl( Program.class ) ).thenReturn( Lists.newArrayList( program ) );

        AnalyticsTableUpdateParams params = AnalyticsTableUpdateParams.newBuilder().withLastYears( 2 ).build();

        when( jdbcTemplate.queryForList( getYearsQuery( program, params ), Integer.class ) )
            .thenReturn( Lists.newArrayList( 2018, 2019 ) );

        subject.populateTable( params,
            PartitionUtils.getTablePartitions( subject.getAnalyticsTables( params ) ).get( 0 ) );

        verify( jdbcTemplate ).execute( sql.capture() );
        String ouQuery = "(select ou.name from organisationunit ou where ou.uid = " + "(select eventdatavalues #>> '{"
            + d5.getUid() + ", value}' from programstageinstance where "
            + "programstageinstanceid=psi.programstageinstanceid )) as \"" + d5.getUid() + "\"";

        assertThat( sql.getValue(), containsString( ouQuery ) );
    }

    @Test
    public void verifyTeiTypeOrgUnitFetchesOuNameWhenPopulatingEventAnalyticsTable()
    {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass( String.class );
        when( databaseInfo.isSpatialSupport() ).thenReturn( true );
        Program p1 = createProgram( 'A' );

        TrackedEntityAttribute tea = createTrackedEntityAttribute( 'a', ValueType.ORGANISATION_UNIT );
        tea.setId( 9999 );

        ProgramTrackedEntityAttribute programTrackedEntityAttribute = createProgramTrackedEntityAttribute( p1, tea );

        p1.setProgramAttributes( Lists.newArrayList( programTrackedEntityAttribute ) );

        when( idObjectManager.getAllNoAcl( Program.class ) ).thenReturn( Lists.newArrayList( p1 ) );

        AnalyticsTableUpdateParams params = AnalyticsTableUpdateParams.newBuilder().withLastYears( 2 ).build();

        when( jdbcTemplate.queryForList( getYearsQuery( p1, params ), Integer.class ) )
                .thenReturn( Lists.newArrayList( 2018, 2019 ) );

        subject.populateTable( params,
                PartitionUtils.getTablePartitions( subject.getAnalyticsTables( params ) ).get( 0 ) );

        verify( jdbcTemplate ).execute( sql.capture() );

        String ouQuery = "(select ou.name from organisationunit ou where ou.uid = " +
                "(select value from trackedentityattributevalue where trackedentityinstanceid=pi.trackedentityinstanceid and " +
                "trackedentityattributeid=9999)) as \"" + tea.getUid() + "\"";

        assertThat( sql.getValue(), containsString( ouQuery ) );
    }

    @Test
    public void verifyGetAnalyticsTableWithOuLevels()
    {
        List<OrganisationUnitLevel> ouLevels = rnd.randomObjects( OrganisationUnitLevel.class, 2 );
        Program programA = rnd.randomObject( Program.class );

        when( idObjectManager.getAllNoAcl( Program.class ) ).thenReturn( Collections.singletonList( programA ) );
        when( organisationUnitService.getFilledOrganisationUnitLevels() ).thenReturn( ouLevels );

        AnalyticsTableUpdateParams params = AnalyticsTableUpdateParams.newBuilder().build();
        when( jdbcTemplate.queryForList( getYearsQuery( programA, params), Integer.class ) )
                .thenReturn( Collections.singletonList( 2019 ) );

        List<AnalyticsTable> tables = subject.getAnalyticsTables( params );

        assertThat( tables, hasSize( 1 ) );

        new AnalyticsTableAsserter.Builder( tables.get(0) )
                .withTableName( TABLE_PREFIX + programA.getUid().toLowerCase() )
                .withTableType( AnalyticsTableType.EVENT )
                .withColumnSize( subject.getFixedColumns().size()
                        + PeriodType.getAvailablePeriodTypes().size() + ouLevels.size() + (programA.isRegistration() ? 2 : 0) )
                .addColumns( periodColumns )
                .withDefaultColumns( subject.getFixedColumns() )
                .addColumn( quote( "uidlevel" + ouLevels.get(0).getLevel() ), col -> match(ouLevels.get(0), col))
                .addColumn( quote( "uidlevel" + ouLevels.get(1).getLevel() ), col -> match(ouLevels.get(1), col))
                .build().verify();
    }

    @Test
    public void verifyGetAnalyticsTableWithOuGroupSet()
    {
        List<OrganisationUnitGroupSet> ouGroupSet = rnd.randomObjects( OrganisationUnitGroupSet.class, 2 );
        Program programA = rnd.randomObject( Program.class );

        when( idObjectManager.getAllNoAcl( Program.class ) ).thenReturn( Collections.singletonList( programA ) );
        when( idObjectManager.getDataDimensionsNoAcl( OrganisationUnitGroupSet.class ) ).thenReturn( ouGroupSet );

        AnalyticsTableUpdateParams params = AnalyticsTableUpdateParams.newBuilder().build();
        when( jdbcTemplate.queryForList( getYearsQuery( programA, params), Integer.class ) )
                .thenReturn( Collections.singletonList( 2019 ) );

        List<AnalyticsTable> tables = subject.getAnalyticsTables( params );

        assertThat( tables, hasSize( 1 ) );

        new AnalyticsTableAsserter.Builder( tables.get(0) )
                .withTableName( TABLE_PREFIX + programA.getUid().toLowerCase() )
                .withTableType( AnalyticsTableType.EVENT )
                .withColumnSize( subject.getFixedColumns().size()
                        + PeriodType.getAvailablePeriodTypes().size() + ouGroupSet.size() + (programA.isRegistration() ? 2 : 0) )
                .addColumns( periodColumns )
                .withDefaultColumns( subject.getFixedColumns() )
                .addColumn( quote( ouGroupSet.get(0).getUid()), col -> match(ouGroupSet.get(0), col))
                .addColumn( quote( ouGroupSet.get(1).getUid()), col -> match(ouGroupSet.get(1), col))
                .build().verify();
    }

    @Test
    public void verifyGetAnalyticsTableWithOptionGroupSets()
    {
        List<CategoryOptionGroupSet> cogs = rnd.randomObjects( CategoryOptionGroupSet.class, 2 );
        Program programA = rnd.randomObject( Program.class );

        when( idObjectManager.getAllNoAcl( Program.class ) ).thenReturn( Collections.singletonList( programA ) );
        when( categoryService.getAttributeCategoryOptionGroupSetsNoAcl() ).thenReturn( cogs );

        AnalyticsTableUpdateParams params = AnalyticsTableUpdateParams.newBuilder().build();
        when( jdbcTemplate.queryForList( getYearsQuery( programA, params), Integer.class ) )
                .thenReturn( Collections.singletonList( 2019 ) );

        List<AnalyticsTable> tables = subject.getAnalyticsTables( params );

        assertThat( tables, hasSize( 1 ) );

        new AnalyticsTableAsserter.Builder( tables.get(0) )
                .withTableName( TABLE_PREFIX + programA.getUid().toLowerCase() )
                .withTableType( AnalyticsTableType.EVENT )
                .withColumnSize( subject.getFixedColumns().size()
                        + PeriodType.getAvailablePeriodTypes().size() + cogs.size() + (programA.isRegistration() ? 2 : 0) )
                .addColumns( periodColumns )
                .withDefaultColumns( subject.getFixedColumns() )
                .addColumn( quote( cogs.get(0).getUid() ), col -> match(cogs.get(0), col))
                .addColumn( quote( cogs.get(1).getUid()), col -> match(cogs.get(1), col))
                .build().verify();
    }
    private void match( OrganisationUnitGroupSet ouGroupSet, AnalyticsTableColumn col )
    {
        String name = quote( ouGroupSet.getUid() );
        assertNotNull( col );
        assertThat( col.getAlias(), is( "ougs." + name ) );
        match(col);
    }

    private void match( OrganisationUnitLevel ouLevel, AnalyticsTableColumn col )
    {
        String name = quote( "uidlevel" + ouLevel.getLevel() );
        assertNotNull( col );
        assertThat( col.getAlias(), is( "ous." + name ) );
        match(col);
    }

    private void match( CategoryOptionGroupSet cog, AnalyticsTableColumn col )
    {
        String name = quote( cog.getUid() );
        assertNotNull( col );
        assertThat( col.getAlias(), is( "acs." + name ) );
        match(col);
    }

    private void match( AnalyticsTableColumn col )
    {
        assertNotNull( col.getCreated() );
        assertThat( col.getDataType(), is( CHARACTER_11 ) );
        assertThat( col.isSkipIndex(), is( false ) );
        assertThat( col.getNotNull(), is( ColumnNotNullConstraint.NULL ) );
        assertThat( col.getIndexColumns(), hasSize( 0 ) );
    }

    private String quote( String string )
    {
        return "\"" + string + "\"";
    }

    @Test
    public void verifyTeaTypeOrgUnitFetchesOuNameWhenPopulatingEventAnalyticsTable()
    {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass( String.class );
        when( databaseInfo.isSpatialSupport() ).thenReturn( true );
        Program p1 = createProgram( 'A' );

        TrackedEntityAttribute tea = createTrackedEntityAttribute( 'a', ValueType.ORGANISATION_UNIT );
        tea.setId( 9999 );

        ProgramTrackedEntityAttribute programTrackedEntityAttribute = createProgramTrackedEntityAttribute( p1, tea );

        p1.setProgramAttributes( Lists.newArrayList( programTrackedEntityAttribute ) );

        when( idObjectManager.getAllNoAcl( Program.class ) ).thenReturn( Lists.newArrayList( p1 ) );

        AnalyticsTableUpdateParams params = AnalyticsTableUpdateParams.newBuilder().withLastYears( 2 ).build();

        subject.populateTable( params,
                PartitionUtils.getTablePartitions( subject.getAnalyticsTables( params ) ).get( 0 ) );

        verify( jdbcTemplate ).execute( sql.capture() );

        String ouQuery = "(select ou.name from organisationunit ou where ou.uid = " +
                "(select value from trackedentityattributevalue where trackedentityinstanceid=pi.trackedentityinstanceid and " +
                "trackedentityattributeid=9999)) as \"" + tea.getUid() + "\"";

        assertThat( sql.getValue(), containsString( ouQuery ) );
    }
    private String toAlias( String template, String uid )
    {
        return String.format( template, uid, uid, uid );
    }

    private void addCategoryCombo( Program program, CategoryCombo categoryCombo )
    {
        program.setCategoryCombo( categoryCombo );
    }

    private String getYearsQuery( Program p, AnalyticsTableUpdateParams params )
    {
        return "select distinct(extract(year from psi.executiondate)) from programstageinstance psi inner join "
            + "programinstance pi on psi.programinstanceid = pi.programinstanceid where pi.programid = " + p.getId()
            + " and psi.executiondate is not null and psi.deleted is false " + (params.getFromDate() != null
                ? "and psi.executiondate >= '" + DateUtils.getMediumDateString( params.getFromDate() ) + "'"
                : "");
    }
}
