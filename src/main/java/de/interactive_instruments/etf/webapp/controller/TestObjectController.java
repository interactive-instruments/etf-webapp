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

import static de.interactive_instruments.etf.webapp.SwaggerConfig.TEST_OBJECTS_TAG_NAME;
import static de.interactive_instruments.etf.webapp.dto.DocumentationConstants.*;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.bind.JAXBException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.apache.commons.io.filefilter.RegexFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.propertyeditors.CustomDateEditor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import springfox.documentation.annotations.ApiIgnore;

import de.interactive_instruments.*;
import de.interactive_instruments.etf.dal.dao.*;
import de.interactive_instruments.etf.dal.dto.capabilities.ResourceDto;
import de.interactive_instruments.etf.dal.dto.capabilities.TestObjectDto;
import de.interactive_instruments.etf.model.EID;
import de.interactive_instruments.etf.model.EidFactory;
import de.interactive_instruments.etf.model.OutputFormat;
import de.interactive_instruments.etf.webapp.WebAppConstants;
import de.interactive_instruments.etf.webapp.conversion.EidConverter;
import de.interactive_instruments.etf.webapp.dto.ApiError;
import de.interactive_instruments.etf.webapp.dto.CreateReusableTestObjectRequest;
import de.interactive_instruments.etf.webapp.dto.TObjectValidator;
import de.interactive_instruments.etf.webapp.dto.TestObjectCreationResponse;
import de.interactive_instruments.etf.webapp.helpers.SimpleFilter;
import de.interactive_instruments.etf.webapp.helpers.User;
import de.interactive_instruments.etf.webapp.helpers.View;
import de.interactive_instruments.exceptions.ObjectWithIdNotFoundException;
import de.interactive_instruments.exceptions.StorageException;
import de.interactive_instruments.exceptions.config.InvalidPropertyException;
import de.interactive_instruments.exceptions.config.MissingPropertyException;
import de.interactive_instruments.io.*;
import de.interactive_instruments.properties.PropertyHolder;
import io.swagger.annotations.*;

/**
 * Test object controller used for managing test objects
 */
@RestController
public class TestObjectController implements PreparedDtoResolver<TestObjectDto> {

    @Autowired
    private EtfConfigController etfConfig;

    @Autowired
    private StatusController statusController;

    @Autowired
    private DataStorageService dataStorageService;

    @Autowired
    private StreamingService streaming;

    @Autowired
    private TestObjectTypeDetectionService testObjectTypeDetectionService;

    @Autowired
    private ObjectMapper objectMapper;

    private Timer cleanTimer;
    // 7 minutes after start
    private final long initialDelay = 420000;

    public static final String PATH = "testobjects";
    public final static String TESTOBJECTS_URL = WebAppConstants.API_BASE_URL + "/TestObjects";
    // 7 minutes for adding resources
    private static final long T_CREATION_WINDOW = 7;
    private IFile testDataDir;
    private final Logger logger = LoggerFactory.getLogger(TestObjectController.class);
    private FileStorage fileStorage;
    private FileContentFilterHolder baseFilter;
    private WriteDao<TestObjectDto> testObjectDao;
    private final Cache<EID, TestObjectDto> transientTestObjects = Caffeine.newBuilder().expireAfterWrite(
            T_CREATION_WINDOW, TimeUnit.MINUTES).build();

    private final static String TEST_OBJECT_DESCRIPTION = "The Test Object model is described in the "
            + "[XML schema documentation](https://resources.etf-validator.net/schema/v2/doc/capabilities_xsd.html#TestObject). "
            + ETF_ITEM_COLLECTION_DESCRIPTION;

    private static class TestObjectCleaner implements ExpirationItemHolder {
        private final WriteDao<TestObjectDto> testObjectDao;
        private final IFile testDataDir;
        private final static Logger logger = LoggerFactory.getLogger(TestObjectCleaner.class);

        private TestObjectCleaner(final Dao<TestObjectDto> testObjectDao,
                final IFile testDataDir) {
            this.testObjectDao = (WriteDao<TestObjectDto>) testObjectDao;
            this.testDataDir = testDataDir;
        }

        @Override
        public void removeExpiredItems(final long maxLifeTime, final TimeUnit unit) {
            int removed = 0;
            try {
                // TODO filter dtos by timestamp and temporary property
                final PreparedDtoCollection<TestObjectDto> all = testObjectDao.getAll(SimpleFilter.allItems());
                for (final TestObjectDto testObjectDto : all) {
                    if ("true".equals(testObjectDto.properties().getPropertyOrDefault("temporary", "false"))) {
                        final long expirationTime = testObjectDto.getCreationDate().getTime() + unit.toMillis(maxLifeTime);
                        if (System.currentTimeMillis() > expirationTime) {
                            final Map<String, ResourceDto> res = testObjectDto.getResources();
                            if (res != null) {
                                for (final ResourceDto resourceDto : res.values()) {
                                    final URI uri = resourceDto.getUri();
                                    if (UriUtils.isFile(uri)) {
                                        final IFile dir = testDataDir.secureExpandPathDown(uri.getPath());
                                        try {
                                            removed++;
                                            dir.deleteDirectory();
                                        } catch (IOException e) {
                                            logger.warn("Error deleting test data directory ", e);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (final StorageException e) {
                logger.warn("Error deleting expired item ", e);
            }
            logger.info("{} items were cleaned.", removed);
        }
    }

    @InitBinder
    private void initBinder(WebDataBinder binder) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss.zzz");
        binder.registerCustomEditor(Date.class, new CustomDateEditor(dateFormat, false));
        binder.setValidator(new TObjectValidator());
    }

    @PreDestroy
    private void shutdown() {
        testObjectDao.release();

        if (this.cleanTimer != null) {
            cleanTimer.cancel();
        }
    }

    private static class GmlAtomFilter implements FileContentFilterHolder {
        private ContentTypeFilter contentFilter = new ContentTypeFilter(
                "application/xml", "application/gml+xml", "application/atom+xml");
        private MultiFileFilter filenameFilter = new FilenameExtensionFilter(".xml", ".gml");

        public ContentTypeFilter content() {
            return this.contentFilter;
        }

        public MultiFileFilter filename() {
            return this.filenameFilter;
        }
    }

    @PostConstruct
    public void init() throws IOException, JAXBException, MissingPropertyException, InvalidPropertyException {

        testDataDir = etfConfig.getPropertyAsFile(EtfConfigController.ETF_TESTDATA_DIR);
        testDataDir.ensureDir();
        logger.info("TEST_DATA_DIR " + testDataDir.getAbsolutePath());

        final IFile tmpUploadDir = etfConfig.getPropertyAsFile(EtfConfigController.ETF_TESTDATA_UPLOAD_DIR);
        if (tmpUploadDir.exists()) {
            tmpUploadDir.deleteDirectory();
        }
        tmpUploadDir.mkdir();

        // TODO provide different filters for each fileStorage
        baseFilter = new GmlAtomFilter();

        // TODO provide file storages for each Test Object Type
        fileStorage = new FileStorage(testDataDir, tmpUploadDir, baseFilter);
        fileStorage.setMaxStorageSize(etfConfig.getPropertyAsLong(EtfConfigController.ETF_TEST_OBJECT_MAX_SIZE));

        logger.info("TMP_HTTP_UPLOADS: " + tmpUploadDir.getAbsolutePath());

        testObjectDao = ((WriteDao<TestObjectDto>) dataStorageService.getDao(TestObjectDto.class));

        final long exp = etfConfig.getPropertyAsLong(EtfConfigController.ETF_TESTOBJECT_UPLOADED_LIFETIME_EXPIRATION);
        if (exp > 0) {
            cleanTimer = new Timer(true);
            final TimedExpiredItemsRemover timedExpiredItemsRemover = new TimedExpiredItemsRemover();
            timedExpiredItemsRemover.addExpirationItemHolder(new TestObjectCleaner(testObjectDao, testDataDir), exp,
                    TimeUnit.MINUTES);
            cleanTimer.scheduleAtFixedRate(timedExpiredItemsRemover,
                    TimeUnit.SECONDS.toMillis(TimeUtils.calcDelay(0, 9, 0)),
                    86400000);
            logger.info("Temporary Test Objects older than {} minutes are removed.", exp);
        }

        logger.info("Test Object controller initialized!");
    }

    Collection<TestObjectDto> getTestObjects() throws StorageException {
        return testObjectDao.getAll(null).asCollection();
    }

    TestObjectDto getTestObjectById(final EID id) throws StorageException, ObjectWithIdNotFoundException {
        return testObjectDao.getById(id).getDto();
    }

    @Override
    public PreparedDto<TestObjectDto> getById(final EID eid, final Filter filter)
            throws StorageException, ObjectWithIdNotFoundException {
        final TestObjectDto testObjectDto = transientTestObjects.getIfPresent(eid);
        if (testObjectDto != null) {
            return new PreparedDto<TestObjectDto>() {
                @Override
                public EID getDtoId() {
                    return testObjectDto.getId();
                }

                @Override
                public TestObjectDto getDto() {
                    return testObjectDto;
                }

                @Override
                public void streamTo(final OutputFormat outputFormat, final PropertyHolder propertyHolder,
                        final OutputStream outputStream) throws IOException {
                    throw new IOException("pseudo dto");
                }

                @Override
                public int compareTo(final PreparedDto o) {
                    return testObjectDto.compareTo(o);
                }
            };
        }
        return this.testObjectDao.getById(eid, filter);
    }

    @Override
    public PreparedDtoCollection<TestObjectDto> getByIds(final Set<EID> eids, final Filter filter)
            throws StorageException, ObjectWithIdNotFoundException {
        return this.testObjectDao.getByIds(eids, filter);
    }

    private void createWithUrlResources(final TestObjectDto testObject) throws LocalizableApiError {

        String hash;
        try {
            final URI serviceEndpoint = testObject.getResourceByName("serviceEndpoint");

            if (etfConfig.getProperty("etf.testobject.allow.privatenet.access").equals("false")) {
                if (UriUtils.isPrivateNet(serviceEndpoint)) {
                    throw new LocalizableApiError("l.rejected.private.subnet.access", false, 403);
                }
            }

            hash = UriUtils.hashFromContent(serviceEndpoint,
                    Credentials.fromProperties(testObject.properties()));
        } catch (final UriUtils.ConnectionException e) {
            if (e.getResponseCode() == 400 && e.getUrl() != null) {
                hash = "0000000000000400";
            } else if ((e.getResponseCode() == 403 || e.getResponseCode() == 401) && e.getUrl() != null) {
                throw new LocalizableApiError("l.url.secured", false, 400, e, e.getUrl().getHost());
            } else if (e.getResponseCode() >= 401 && e.getResponseCode() < 500) {
                throw new LocalizableApiError("l.url.client.error", e);
            } else if (e.getResponseCode() != -1) {
                throw new LocalizableApiError("l.url.server.error", e);
            } else if (e.getCause() instanceof UnknownHostException && e.getUrl() != null) {
                throw new LocalizableApiError("l.unknown.host", false, 400, e, e.getUrl().getHost());
            } else {
                throw new LocalizableApiError("l.invalid.url", e);
            }
        } catch (IllegalArgumentException | IOException e) {
            throw new LocalizableApiError("l.invalid.url", e);
        }
        testObject.setItemHash(hash);
    }

    private void createWithFileResources(final TestObjectDto testObject,
            final Collection<List<MultipartFile>> uploadFiles)
            throws IOException, LocalizableApiError, InvalidPropertyException {

        if (uploadFiles != null && !uploadFiles.isEmpty()) {
            long size = 0;
            for (final List<MultipartFile> uploadFileL : uploadFiles) {
                for (final MultipartFile multipartFile : uploadFileL) {
                    size += multipartFile.getSize();
                }
            }
            if (size > etfConfig.getPropertyAsLong(EtfConfigController.ETF_MAX_UPLOAD_SIZE)) {
                throw new LocalizableApiError("l.max.upload.size.exceeded", false, 400);
            }
        }

        // Regex
        final String regex = testObject.properties().getProperty("regex");
        final MultiFileFilter combinedFileFilter;
        final RegexFileFilter additionalRegexFilter;
        if (SUtils.isNullOrEmpty(regex)) {
            additionalRegexFilter = null;
            combinedFileFilter = baseFilter.filename();
        } else {
            additionalRegexFilter = new RegexFileFilter(regex);
            combinedFileFilter = baseFilter.filename().and(additionalRegexFilter);
        }

        final IFile testObjectDir;
        final URI resURI = testObject.getResourceByName("data");
        final String resourceName;
        if (resURI != null) {
            if (UriUtils.isFile(resURI)) {
                // Relative path in test object directory
                testObjectDir = testDataDir.secureExpandPathDown(
                        resURI.getPath());
                testObjectDir.expectDirIsReadable();
                resourceName = "data";
            } else {
                // URL
                final Credentials credentials = Credentials.fromProperties(testObject.properties());
                final FileStorage.DownloadCmd downloadCmd = fileStorage.download(
                        testObject, additionalRegexFilter, credentials, resURI);
                testObjectDir = downloadCmd.download();
                resourceName = "download." + testObject.getResourcesSize();
            }
        } else if (uploadFiles != null && !uploadFiles.isEmpty()) {
            final FileStorage.UploadCmd uploadCmd = this.fileStorage.upload(testObject, additionalRegexFilter, uploadFiles);
            testObjectDir = uploadCmd.upload();
            resourceName = "upload." + testObject.getResourcesSize();
        } else {
            throw new LocalizableApiError("l.testobject.required", false, 400);
        }

        // Add new resource
        testObject.addResource(new ResourceDto(resourceName, testObjectDir.toURI()));

        final FileHashVisitor v = new FileHashVisitor(combinedFileFilter);
        Files.walkFileTree(new File(testObject.getResourceByName(resourceName)).toPath(),
                EnumSet.of(FileVisitOption.FOLLOW_LINKS), 5, v);
        if (v.getFileCount() == 0) {
            if (regex != null && !regex.isEmpty()) {
                throw new LocalizableApiError("l.testObject.regex.null.selection", false, 400, regex);
            } else {
                throw new LocalizableApiError("l.testObject.testdir.no.xml.gml.found", false, 400);
            }
        }
        if (v.getSize() == 0) {
            if (v.getFileCount() == 1) {
                throw new LocalizableApiError("l.testObject.one.file.with.zero.size", false, 400, v.getFileCount());
            } else {
                throw new LocalizableApiError("l.testObject.multiple.files.with.zero.size", false, 400, v.getFileCount());
            }
        }

        testObject.setItemHash(v.getHash());
        testObject.properties().setProperty("indexed", "false");
        testObject.properties().setProperty("files", String.valueOf(v.getFileCount()));
        testObject.properties().setProperty("size", String.valueOf(v.getSize()));
        testObject.properties().setProperty("sizeHR", FileUtils.byteCountToDisplayRoundedSize(v.getSize(),
                2, LocaleContextHolder.getLocale()));
        if (v.getSkippedFiles() > 0) {
            testObject.properties().setProperty("skippedFiles", String.valueOf(v.getSkippedFiles()));
        }
        if (v.getEmptyFiles() > 0) {
            testObject.properties().setProperty("emptyFiles", String.valueOf(v.getEmptyFiles()));
        }
    }

    // Main entry point for Test Run contoller
    public void initResourcesAndAdd(final TestObjectDto testObject, final Set<EID> supportedTestObjectTypes)
            throws IOException, ObjectWithIdNotFoundException, LocalizableApiError, InvalidPropertyException {

        // If the ID is null, the Test Object references external data
        if (testObject.getId() == null) {
            // Provide a new ID
            testObject.setId(EidFactory.getDefault().createRandomId());

            // If the TestObject possess resources, it is either a service based TestObject
            // or it is a file based Test Object with either a relative path to TestData or
            // an URL to testdata that need to be downloaded.
            testObject.setItemHash("0");
            if (testObject.getResourceByName("serviceEndpoint") != null) {
                // Reference service
                createWithUrlResources(testObject);
            } else {
                // Download referenced files if there is a "data" resource
                createWithFileResources(testObject, null);
            }
            if (testObject.getAuthor() == null) {
                testObject.setAuthor("unknown");
            }
        }
        testObjectTypeDetectionService.checkAndResolveTypes(testObject, supportedTestObjectTypes);
        // otherwise it contains all required types.

        testObject.setVersionFromStr("1.0.0");
        testObject.setLastUpdateDate(new Date());
        if (testObject.getLastEditor() == null) {
            final String author = testObject.getAuthor();
            testObject.setLastEditor(SUtils.isNullOrEmpty(author) ? "unknown" : author);
        }
        testObject.setLocalPath(".");
        testObject.properties().setProperty("data.downloadable", "false");

        testObjectDao.add(testObject);
    }

    //
    // Rest interfaces
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    // Undocumented interface for internal use
    @RequestMapping(value = {WebAppConstants.API_BASE_URL + "/TestDataDirs"}, method = RequestMethod.GET)
    public List<String> testDataDirs() {
        if (!"standard".equals(View.getWorkflowType())) {
            // forbidden in non standard workflow
            return null;
        }
        final List<String> testDataDirs = new ArrayList<>();
        final File[] files = this.testDataDir.listFiles();
        if (files != null && files.length != 0) {
            Arrays.sort(files);
            for (final File file : files) {
                if (file.isDirectory()) {
                    testDataDirs.add(file.getName());
                }
            }
        }
        return testDataDirs;
    }

    @ApiOperation(value = "Get Test Object as JSON", notes = TEST_OBJECT_DESCRIPTION, tags = {TEST_OBJECTS_TAG_NAME})
    @RequestMapping(value = {TESTOBJECTS_URL + "/{id}",
            TESTOBJECTS_URL + "/{id}.json"}, method = RequestMethod.GET, produces = "application/json")
    public void testObjectByIdJson(@PathVariable String id,
            HttpServletRequest request, HttpServletResponse response)
            throws IOException, ObjectWithIdNotFoundException, LocalizableApiError {
        if (transientTestObjects.getIfPresent(EidConverter.toEid(id)) != null) {
            throw new LocalizableApiError("l.temporary.testobject.access", false, 404);
        }
        streaming.asJson2(testObjectDao, request, response, id);
    }

    @ApiOperation(value = "Get multiple Test Objects as JSON", notes = TEST_OBJECT_DESCRIPTION, tags = {TEST_OBJECTS_TAG_NAME})
    @RequestMapping(value = {TESTOBJECTS_URL, TESTOBJECTS_URL + ".json"}, method = RequestMethod.GET)
    public void listTestObjectsJson(
            @ApiParam(value = OFFSET_DESCRIPTION) @RequestParam(required = false, defaultValue = "0") int offset,
            @ApiParam(value = LIMIT_DESCRIPTION) @RequestParam(required = false, defaultValue = "0") int limit,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        streaming.asJson2(testObjectDao, request, response, new SimpleFilter(offset, limit));
    }

    @ApiOperation(value = "Get multiple Test Objects as XML", notes = TEST_OBJECT_DESCRIPTION, tags = {
            TEST_OBJECTS_TAG_NAME}, produces = "text/xml")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "EtfItemCollection with multiple Test Objects", reference = "www.interactive-instruments.de")
    })
    @RequestMapping(value = {TESTOBJECTS_URL + ".xml"}, method = RequestMethod.GET)
    public void listTestObjectXml(
            @RequestParam(required = false, defaultValue = "0") int offset,
            @RequestParam(required = false, defaultValue = "0") int limit,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        streaming.asXml2(testObjectDao, request, response, new SimpleFilter(offset, limit));
    }

    @ApiOperation(value = "Get Test Object as XML", notes = TEST_OBJECT_DESCRIPTION, tags = {
            TEST_OBJECTS_TAG_NAME}, produces = "text/xml")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Test Object", reference = "www.interactive-instruments.de"),
            @ApiResponse(code = 404, message = "Test Object not found")
    })
    @RequestMapping(value = {TESTOBJECTS_URL + "/{id}.xml"}, method = RequestMethod.GET)
    public void testObjectByIdXml(
            @ApiParam(value = "ID of Test Object that needs to be fetched", example = "EID-1ffe6ea2-5c29-4ce9-9a7e-f4d9d71119e8", required = true) @PathVariable String id,
            HttpServletRequest request, HttpServletResponse response)
            throws IOException, ObjectWithIdNotFoundException, LocalizableApiError {
        if (transientTestObjects.getIfPresent(EidConverter.toEid(id)) != null) {
            throw new LocalizableApiError("l.temporary.testobject.access", false, 404);
        }
        streaming.asXml2(testObjectDao, request, response, id);
    }

    @ApiOperation(value = "Delete Test Object", tags = {TEST_OBJECTS_TAG_NAME})
    @ApiResponses(value = {
            @ApiResponse(code = 204, message = "Test Object deleted"),
            @ApiResponse(code = 404, message = "Test Object not found")
    })
    @RequestMapping(value = TESTOBJECTS_URL + "/{id}", method = RequestMethod.DELETE)
    public ResponseEntity<String> delete(@PathVariable String id, HttpServletResponse response)
            throws ObjectWithIdNotFoundException, IOException {
        final ResponseEntity<String> exists = exists(id);
        if (!HttpStatus.NOT_FOUND.equals(exists.getStatusCode())) {
            this.testObjectDao.delete(EidConverter.toEid(id));
        }
        return exists;
    }

    @ApiOperation(value = "Check if Test Object exists", notes = "Please note that this interface will always return HTTP status code '404' for temporary Test Object IDs.", tags = {
            TEST_OBJECTS_TAG_NAME})
    @ApiResponses(value = {
            @ApiResponse(code = 204, message = "Test Object exists"),
            @ApiResponse(code = 404, message = "Test Object does not exist")
    })
    @RequestMapping(value = {TESTOBJECTS_URL + "/{id}"}, method = RequestMethod.HEAD)
    public ResponseEntity<String> exists(
            @PathVariable String id) {
        return testObjectDao.exists(EidConverter.toEid(id)) ? new ResponseEntity<>(HttpStatus.NO_CONTENT)
                : new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    @ApiOperation(value = "Create a new Test Object", notes = "Based on whether a test object is specified with the "
            + "'testobject' property, the service will either create a reusable Test Object with the provided properties or "
            + "create a TEMPORARY Test Object which can be used for one Test Run."
            + "On success the service will return it's ID which afterwards can be used to start a new Test Run. "
            + "If the ID of a temporary Test Object is not used within 5 minutes, the Test Object and all uploaded data will "
            + "be deleted automatically. "
            + "PLEASE NOTE: A TEMPORARY Test Object will not be persisted as long as it is not used in a Test Run. "
            + "A TEMPORARY Test Object can not be retrieved or deleted but can only be referenced from a 'Test Run Request' to start a new Test Run. "
            + "The property 'data.downloadable' of a TEMPORARY Test Object is always set to true. "
            + "Also note that the Swagger UI does only allow single file uploads in contrast to the API which allows multi file uploads.",

            tags = {TEST_OBJECTS_TAG_NAME}, consumes = "multipart/form-data", produces = "application/json")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "fileupload", dataType = "__file", paramType = "form"),
            @ApiImplicitParam(name = "testobject", dataType = "object", dataTypeClass = CreateReusableTestObjectRequest.class, paramType = "form"),
    })
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Test Object created", response = TestObjectCreationResponse.class),
            @ApiResponse(code = 400, message = "Test Object creation failed", response = ApiError.class),
            @ApiResponse(code = 413, message = "Uploaded test data are too large", response = ApiError.class)
    })
    @RequestMapping(value = {TESTOBJECTS_URL}, method = RequestMethod.POST, consumes = "multipart/form-data")
    public TestObjectCreationResponse uploadData(
            @ApiIgnore final MultipartHttpServletRequest request)
            throws LocalizableApiError, InvalidPropertyException, ObjectWithIdNotFoundException, IOException {

        final TestObjectCreationResponse testObjectUploadResponse;
        // there seems to be no way to solve this reasonably with annotations..
        final String testObjectStr = request.getParameter("testobject");
        if (!SUtils.isNullOrEmpty(testObjectStr) && testObjectStr.length() > 3) {
            testObjectUploadResponse = createReusableTestObject(request);
        } else {
            statusController.ensureStatusNotMajor();
            testObjectUploadResponse = createAdHocTestObject(request);
        }
        return testObjectUploadResponse;
    }

    private TestObjectCreationResponse createAdHocTestObject(final MultipartHttpServletRequest request)
            throws InvalidPropertyException, ObjectWithIdNotFoundException {
        final TestObjectDto testObject = new TestObjectDto();
        testObject.properties().setProperty("temporary", "true");
        final TestObjectCreationResponse testObjectUploadResponse = createAndDetectTestObject(request, testObject);
        this.transientTestObjects.put(testObject.getId(), testObject);
        testObject.setLabel(testObjectUploadResponse.getNameForUpload());
        return testObjectUploadResponse;
    }

    private TestObjectCreationResponse createReusableTestObject(final MultipartHttpServletRequest request)
            throws IOException, ObjectWithIdNotFoundException, InvalidPropertyException {
        final String testObjectStr = request.getParameter("testobject");
        final CreateReusableTestObjectRequest testObjectRequest = objectMapper.readValue(testObjectStr,
                CreateReusableTestObjectRequest.class);

        final TestObjectDto testObject;
        try {
            testObject = testObjectRequest.toTestObject();
        } catch (URISyntaxException e) {
            throw new LocalizableApiError(e);
        }
        final TestObjectCreationResponse testObjectUploadResponse = createAndDetectTestObject(request, testObject);
        testObjectDao.add(testObject);
        return testObjectUploadResponse;
    }

    private TestObjectCreationResponse createAndDetectTestObject(final MultipartHttpServletRequest request,
            final TestObjectDto testObject)
            throws InvalidPropertyException, ObjectWithIdNotFoundException {
        testObject.setId(EidFactory.getDefault().createRandomId());
        try {
            // Create from upload
            createWithFileResources(testObject, request.getMultiFileMap().values());

            testObjectTypeDetectionService.checkAndResolveTypes(testObject, null);
        } catch (StorageException e) {
            throw new LocalizableApiError(e);
        } catch (IOException e) {
            throw new LocalizableApiError(e);
        }
        testObject.setLastUpdateDateNow();
        testObject.setLastEditor(User.getUser(request));
        return new TestObjectCreationResponse(testObject, request.getMultiFileMap().values());
    }

    @ApiOperation(value = "Get all Test Object resources", notes = "Download the resources of the Test Object."
            + " The creator of the object can prevent this setting the property 'data.downloadable' to 'false'."
            + " Temporary test objects cannot be downloaded, 'data.downloadable' is set to 'false' by default.", tags = {
                    TEST_OBJECTS_TAG_NAME})
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Test Object resources returned"),
            @ApiResponse(code = 400, message = "Invalid Test Object ID", response = ApiError.class),
            @ApiResponse(code = 403, message = "Resource download forbidden"),
            @ApiResponse(code = 404, message = "Test Object not found", response = ApiError.class)
    })
    @RequestMapping(value = {TESTOBJECTS_URL + "/{id}/data"}, method = RequestMethod.GET)
    public void getResources(
            @ApiParam(value = EID_DESCRIPTION, example = EID_EXAMPLE, required = true) @PathVariable String id,
            final HttpServletResponse response)
            throws IOException, ObjectWithIdNotFoundException, LocalizableApiError {
        final TestObjectDto testObject = testObjectDao.getById(EidConverter.toEid(id)).getDto();
        if ("true".equals(testObject.properties().getPropertyOrDefault("data.downloadable", "false"))) {
            if (testObject.getResourcesSize() == 1) {
                final URI uri = testObject.getResourceCollection().iterator().next().getUri();
                if (!UriUtils.isFile(uri)) {
                    // stream url
                    UriUtils.stream(uri, response.getOutputStream());
                } else {
                    // compress one file/dir
                    final IFile file = new IFile(uri);
                    response.setContentType("application/zip");
                    response.setHeader("Content-disposition",
                            "attachment; filename=\"TestObject." + id + ".zip\"");
                    file.compressTo(response.getOutputStream());
                }
            } else {
                // compress multiple files
                final List<IFile> tmpDownloadedFiles = new ArrayList<>();
                try {
                    final List<IFile> files = new ArrayList<>();
                    for (final ResourceDto resource : testObject.getResourceCollection()) {
                        final URI uri = resource.getUri();
                        if (!UriUtils.isFile(uri)) {
                            // download
                            final IFile tmpFile = UriUtils.download(uri);
                            tmpDownloadedFiles.add(tmpFile);
                            files.add(tmpFile);
                        } else {
                            files.add(new IFile(uri));
                        }
                    }
                    response.setContentType("application/zip");
                    response.setHeader("Content-disposition",
                            "attachment; filename=\"TestObject." + id + ".zip\"");
                    IFile.compressTo(files, response.getOutputStream());
                } finally {
                    tmpDownloadedFiles.forEach(f -> f.delete());
                }
            }
        } else {
            response.setStatus(403);
            response.getWriter().print(
                    "Data download forbidden through \"data.downloadable\" property");
        }
    }
}
