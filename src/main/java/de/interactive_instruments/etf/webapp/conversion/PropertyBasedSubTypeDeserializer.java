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
package de.interactive_instruments.etf.webapp.conversion;

import java.io.IOException;
import java.util.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.interactive_instruments.SUtils;

/**
 * @author Jon Herrmann ( herrmann aT interactive-instruments doT de )
 */
public class PropertyBasedSubTypeDeserializer<T> extends StdDeserializer<T> {

    private final List<String> uniqueIdentifiers = new ArrayList<>();
    private final List<Class<? extends T>> classes = new ArrayList<>();

    PropertyBasedSubTypeDeserializer(final Class<T> clazz) {
        super(clazz);
    }

    void register(final Class<? extends T> clazz, final String uniqueProperty) {
        uniqueIdentifiers.add(uniqueProperty);
        classes.add(clazz);
    }

    @Override
    public T deserialize(final JsonParser jp, final DeserializationContext ctxt) throws IOException {
        final ObjectMapper mapper = (ObjectMapper) jp.getCodec();
        final ObjectNode obj = mapper.readTree(jp);
        int pos = -1;
        for (int i = 0, uniqueIdentifiersSize = uniqueIdentifiers.size(); i < uniqueIdentifiersSize; i++) {
            if (obj.has(uniqueIdentifiers.get(i))) {
                if (pos > -1) {
                    throw ctxt.mappingException("Polymorphic deserialization failed. "
                            + "Several properties were provided although only one of '"
                            + SUtils.concatStr(", ", uniqueIdentifiers) + "' was expected.");
                }
                pos = i;
            }
        }

        if (pos == -1) {
            throw ctxt.mappingException("Polymorphic deserialization failed. "
                    + "Expected exactly one property of '" + SUtils.concatStr(", ", uniqueIdentifiers) + "'");
        }

        return mapper.treeToValue(obj, classes.get(pos));
    }

}
