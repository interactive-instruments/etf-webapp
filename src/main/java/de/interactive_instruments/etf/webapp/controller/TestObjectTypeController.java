/**
 * Copyright 2017-2019 European Union, interactive instruments GmbH
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 *
 * This work was supported by the EU Interoperability Solutions for
 * European Public Administrations Programme (http://ec.europa.eu/isa)
 * through Action 1.17: A Reusable INSPIRE Reference Platform (ARE3NA).
 */
package de.interactive_instruments.etf.webapp.controller;

import static de.interactive_instruments.etf.webapp.SwaggerConfig.SERVICE_CAP_TAG_NAME;
import static de.interactive_instruments.etf.webapp.dto.DocumentationConstants.*;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import de.interactive_instruments.Credentials;
import de.interactive_instruments.UriUtils;
import de.interactive_instruments.etf.component.loaders.LoadingContext;
import de.interactive_instruments.etf.dal.dao.*;
import de.interactive_instruments.etf.dal.dto.capabilities.ResourceDto;
import de.interactive_instruments.etf.dal.dto.capabilities.TestObjectDto;
import de.interactive_instruments.etf.dal.dto.capabilities.TestObjectTypeDto;
import de.interactive_instruments.etf.detector.DetectedTestObjectType;
import de.interactive_instruments.etf.detector.IncompatibleTestObjectTypeException;
import de.interactive_instruments.etf.detector.TestObjectTypeDetectorManager;
import de.interactive_instruments.etf.detector.TestObjectTypeNotDetected;
import de.interactive_instruments.etf.model.EID;
import de.interactive_instruments.etf.model.EidMap;
import de.interactive_instruments.etf.model.capabilities.Resource;
import de.interactive_instruments.etf.webapp.WebAppConstants;
import de.interactive_instruments.etf.webapp.helpers.SimpleFilter;
import de.interactive_instruments.exceptions.ObjectWithIdNotFoundException;
import de.interactive_instruments.exceptions.StorageException;
import de.interactive_instruments.exceptions.config.ConfigurationException;

import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 * @author Jon Herrmann ( herrmann aT interactive-instruments doT de )
 */
@RestController
public class TestObjectTypeController implements PreparedDtoResolver<TestObjectTypeDto> {

    @Autowired
    private DataStorageService dataStorageService;

    @Autowired
    private StreamingService streaming;

    @Autowired
    private LoadingContext loadingContext;

    private final Logger logger = LoggerFactory.getLogger(TestObjectTypeController.class);

    private Dao<TestObjectTypeDto> testObjectTypeDao;
    private final static String TEST_OBJECT_TYPES_URL = WebAppConstants.API_BASE_URL + "/TestObjectTypes";

    private final static String TEST_OBJECT_TYPE_DESCRIPTION = "The Test Object model is described in the "
            + "[XML schema documentation](https://resources.etf-validator.net/schema/v2/doc/capabilities_xsd.html#TestObjectType) "
            + ETF_ITEM_COLLECTION_DESCRIPTION;

    @PostConstruct
    private void init() throws IOException {
        testObjectTypeDao = dataStorageService.getDao(TestObjectTypeDto.class);
        final EidMap<TestObjectTypeDto> supportedTypes = TestObjectTypeDetectorManager.getSupportedTypes();
        ((WriteDao) testObjectTypeDao).deleteAllExisting(supportedTypes.keySet());
        ((WriteDao) testObjectTypeDao).addAll(supportedTypes.values());
        loadingContext.getItemRegistry().register(supportedTypes.values());

        streaming.prepareCache(testObjectTypeDao, SimpleFilter.allItems());
        logger.info("Test Object Type controller initialized");
    }

    @Override
    public PreparedDto<TestObjectTypeDto> getById(final EID id, final Filter filter)
            throws StorageException, ObjectWithIdNotFoundException {
        return testObjectTypeDao.getById(id, filter);
    }

    @Override
    public PreparedDtoCollection<TestObjectTypeDto> getByIds(final Set<EID> id, final Filter filter)
            throws StorageException, ObjectWithIdNotFoundException {
        return testObjectTypeDao.getByIds(id, filter);
    }

    public void checkAndResolveTypes(final TestObjectDto dto, final Set<EID> expectedTypes)
            throws IOException, LocalizableApiError,
            ObjectWithIdNotFoundException {
        // First resource is the main resource
        final ResourceDto resourceDto = dto.getResourceCollection().iterator().next();
        final Resource resource = Resource.create(resourceDto.getName(),
                resourceDto.getUri(), Credentials.fromProperties(dto.properties()));
        final DetectedTestObjectType detectedTestObjectType;
        try {
            detectedTestObjectType = TestObjectTypeDetectorManager.detect(resource, expectedTypes);
        } catch (final TestObjectTypeNotDetected e) {
            throw new LocalizableApiError(e);
        } catch (IncompatibleTestObjectTypeException e) {
            throw new LocalizableApiError(e);
        }
        detectedTestObjectType.enrichAndNormalize(dto);
        if (!UriUtils.isFile(resourceDto.getUri())) {
            // service URI
            dto.setRemoteResource(resourceDto.getUri());
        } else {
            // fallback download URI
            final URI downloadUri = dto.getResourceByName("data");
            if (downloadUri != null && !UriUtils.isFile(downloadUri)) {
                dto.setRemoteResource(downloadUri);
            }
        }
    }

    //
    // Rest interfaces
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @ApiOperation(value = "Get Test Object Type as JSON", notes = TEST_OBJECT_TYPE_DESCRIPTION, tags = {
            SERVICE_CAP_TAG_NAME})
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Test Object Type"),
            @ApiResponse(code = 404, message = "Test Object Type not found")
    })
    @RequestMapping(value = {TEST_OBJECT_TYPES_URL + "/{id}", TEST_OBJECT_TYPES_URL + "/{id}.json"}, method = RequestMethod.GET)
    public void testObjectTypesByIdJson(
            @ApiParam(value = EID_DESCRIPTION, example = EID_EXAMPLE, required = true) @PathVariable String id,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException, ObjectWithIdNotFoundException {
        streaming.asJson2(testObjectTypeDao, request, response, id);
    }

    @ApiOperation(value = "Get multiple Test Object Types as JSON", notes = TEST_OBJECT_TYPE_DESCRIPTION, tags = {
            SERVICE_CAP_TAG_NAME})
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "EtfItemCollection with multiple Test Object Types")
    })
    @RequestMapping(value = {TEST_OBJECT_TYPES_URL, TEST_OBJECT_TYPES_URL + ".json"}, method = RequestMethod.GET)
    public void listTestObjectTypesJson(
            @ApiParam(value = OFFSET_DESCRIPTION) @RequestParam(required = false, defaultValue = "0") int offset,
            @ApiParam(value = LIMIT_DESCRIPTION) @RequestParam(required = false, defaultValue = "0") int limit,
            HttpServletRequest request,
            HttpServletResponse response)
            throws ConfigurationException, IOException, ObjectWithIdNotFoundException {
        streaming.asJson2(testObjectTypeDao, request, response, new SimpleFilter(offset, limit));
    }

    @ApiOperation(value = "Get multiple Test Object Types as XML", notes = TEST_OBJECT_TYPE_DESCRIPTION, tags = {
            SERVICE_CAP_TAG_NAME}, produces = "text/xml")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "EtfItemCollection with multiple Test Object Types")
    })
    @RequestMapping(value = {TEST_OBJECT_TYPES_URL + ".xml"}, method = RequestMethod.GET)
    public void listTestObjectTypesXml(
            @RequestParam(required = false, defaultValue = "0") int offset,
            @RequestParam(required = false, defaultValue = "0") int limit,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException, ObjectWithIdNotFoundException {
        streaming.asXml2(testObjectTypeDao, request, response, new SimpleFilter(offset, limit));
    }

    @ApiOperation(value = "Get Test Object Type as XML", notes = TEST_OBJECT_TYPE_DESCRIPTION, tags = {
            SERVICE_CAP_TAG_NAME}, produces = "text/xml")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Test Object Type"),
            @ApiResponse(code = 404, message = "Test Object Type not found")
    })
    @RequestMapping(value = {TEST_OBJECT_TYPES_URL + "/{id}.xml"}, method = RequestMethod.GET)
    public void testObjectTypesByIdXml(
            @ApiParam(value = EID_DESCRIPTION, example = EID_EXAMPLE, required = true) @PathVariable String id,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException, ObjectWithIdNotFoundException {
        streaming.asXml2(testObjectTypeDao, request, response, id);
    }

}
