package org.hisp.dhis.analytics.orgunit.data;

/*
 * Copyright (c) 2004-2018, University of Oslo
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hisp.dhis.analytics.orgunit.OrgUnitAnalyticsManager;
import org.hisp.dhis.analytics.orgunit.OrgUnitQueryParams;
import org.hisp.dhis.analytics.orgunit.OrgUnitQueryPlanner;
import org.hisp.dhis.analytics.util.GridRenderUtils;
import org.hisp.dhis.analytics.orgunit.OrgUnitAnalyticsService;
import org.hisp.dhis.common.DimensionalObjectUtils;
import org.hisp.dhis.common.Grid;
import org.hisp.dhis.common.GridHeader;
import org.hisp.dhis.common.IdentifiableObjectManager;
import org.hisp.dhis.common.IdentifiableProperty;
import org.hisp.dhis.common.IllegalQueryException;
import org.hisp.dhis.common.MetadataItem;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.commons.util.TextUtils;
import org.hisp.dhis.organisationunit.OrganisationUnit;
import org.hisp.dhis.organisationunit.OrganisationUnitGroupSet;
import org.hisp.dhis.system.grid.ListGrid;
import org.springframework.beans.factory.annotation.Autowired;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.hisp.dhis.common.DimensionalObject.DIMENSION_SEP;

/**
 * @author Lars Helge Overland
 */
public class DefaultOrgUnitAnalyticsService
    implements OrgUnitAnalyticsService
{
    private static final Log log = LogFactory.getLog( DefaultOrgUnitAnalyticsService.class );

    private IdentifiableObjectManager idObjectManager;

    private OrgUnitAnalyticsManager analyticsManager;

    private OrgUnitQueryPlanner queryPlanner;

    //TODO Add outputIdScheme support

    @Autowired
    public DefaultOrgUnitAnalyticsService( IdentifiableObjectManager idObjectManager,
        OrgUnitAnalyticsManager analyticsManager, OrgUnitQueryPlanner queryPlanner )
    {
        checkNotNull( idObjectManager );
        checkNotNull( analyticsManager );
        checkNotNull( queryPlanner );

        this.idObjectManager = idObjectManager;
        this.analyticsManager = analyticsManager;
        this.queryPlanner = queryPlanner;
    }

    @Override
    public OrgUnitQueryParams getParams( String orgUnits, String orgUnitGroupSets, String columns )
    {
        List<String> ous = TextUtils.getOptions( orgUnits );
        List<String> ougs = TextUtils.getOptions( orgUnitGroupSets );
        List<String> cols = TextUtils.getOptions( columns );

        return new OrgUnitQueryParams.Builder()
            .withOrgUnits( idObjectManager.getObjects( OrganisationUnit.class, IdentifiableProperty.UID, ous ) )
            .withOrgUnitGroupSets( idObjectManager.getObjects( OrganisationUnitGroupSet.class, IdentifiableProperty.UID, ougs ) )
            .withColumns( DimensionalObjectUtils.asDimensionalObjectList( idObjectManager.getObjects( OrganisationUnitGroupSet.class, IdentifiableProperty.UID, cols ) ) )
            .build();
    }

    @Override
    public Grid getOrgUnitData( OrgUnitQueryParams params )
    {
        log.info( String.format( "Get org unit data for query: %s", params ) );

        validate( params );

        return params.isTableLayout() ?
            getOrgUnitDataTableLayout( params ) :
            getOrgUnitDataNormalized( params );
    }

    private Grid getOrgUnitDataNormalized( OrgUnitQueryParams params )
    {
        Grid grid = new ListGrid();

        addHeaders( params, grid );
        addMetadata( params, grid );

        getOrgUnitDataMap( params ).entrySet().forEach( entry -> {
            grid.addRow()
                .addValues( entry.getKey().split( DIMENSION_SEP ) )
                .addValue( entry.getValue() );
            } );

        return grid;
    }

    private Grid getOrgUnitDataTableLayout( OrgUnitQueryParams params )
    {
        return GridRenderUtils.asGrid( params.getColumns(), params.getRows(), getOrgUnitDataMap( params ) );
    }

    @Override
    public Map<String, Object> getOrgUnitDataMap( OrgUnitQueryParams params )
    {
        validate( params );

        Map<String, Object> valueMap = new HashMap<>();
        queryPlanner.planQuery( params ).forEach( query -> valueMap.putAll( analyticsManager.getOrgUnitData( query ) ) );
        return valueMap;
    }

    @Override
    public void validate( OrgUnitQueryParams params )
    {
        if ( params == null )
        {
            throw new IllegalQueryException( "Query cannot be null" );
        }

        if ( params.getOrgUnits().isEmpty() )
        {
            throw new IllegalQueryException( "At least one org unit must be specified" );
        }

        if ( params.getOrgUnitGroupSets().isEmpty() )
        {
            throw new IllegalQueryException( "At least one org unit group set must be specified" );
        }
    }

    private void addHeaders( OrgUnitQueryParams params, Grid grid )
    {
        grid.addHeader( new GridHeader( "orgunit", "Organisation unit", ValueType.TEXT, null, false, true ) );
        params.getOrgUnitGroupSets().forEach( ougs ->
            grid.addHeader( new GridHeader( ougs.getUid(), ougs.getDisplayName(), ValueType.TEXT, null, false, true ) ) );
        grid.addHeader( new GridHeader( "count", "Count", ValueType.INTEGER, null, false, false ) );
    }

    private void addMetadata( OrgUnitQueryParams params, Grid grid )
    {
        Map<String, Object> metadata = new HashMap<>();
        Map<String, Object> items = new HashMap<>();

        params.getOrgUnits().stream()
            .forEach( ou -> items.put( ou.getUid(), new MetadataItem( ou.getDisplayName() ) ) );
        params.getOrgUnitGroupSets().stream()
            .map( ougs -> ougs.getOrganisationUnitGroups() )
            .flatMap( oug -> oug.stream() )
            .forEach( oug -> items.put( oug.getUid(), new MetadataItem( oug.getDisplayName() ) ) );

        metadata.put( "items", items );
        grid.setMetaData( metadata );
    }
}
