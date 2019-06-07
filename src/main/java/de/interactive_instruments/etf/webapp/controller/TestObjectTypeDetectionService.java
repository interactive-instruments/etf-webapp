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

import java.io.IOException;
import java.net.URI;
import java.util.Set;

import org.springframework.stereotype.Service;

import de.interactive_instruments.Credentials;
import de.interactive_instruments.UriUtils;
import de.interactive_instruments.etf.dal.dto.capabilities.ResourceDto;
import de.interactive_instruments.etf.dal.dto.capabilities.TestObjectDto;
import de.interactive_instruments.etf.detector.DetectedTestObjectType;
import de.interactive_instruments.etf.detector.IncompatibleTestObjectTypeException;
import de.interactive_instruments.etf.detector.TestObjectTypeDetectorManager;
import de.interactive_instruments.etf.detector.TestObjectTypeNotDetected;
import de.interactive_instruments.etf.model.EID;
import de.interactive_instruments.etf.model.capabilities.Resource;
import de.interactive_instruments.exceptions.ObjectWithIdNotFoundException;

/**
 * @author Jon Herrmann ( herrmann aT interactive-instruments doT de )
 */
@Service
public class TestObjectTypeDetectionService {

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
}
