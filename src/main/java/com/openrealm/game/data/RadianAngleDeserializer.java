package com.openrealm.game.data;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

/**
 * Deserializes a radian-angle field that may be a plain number ({@code 0.5}) or
 * a unit-circle placeholder string ({@code "{{PI/2}}"}, {@code "{{3*PI/4}}"}).
 * Without this, a single placeholder-valued angle aborts the whole array parse.
 */
public class RadianAngleDeserializer extends JsonDeserializer<Float> {

    @Override
    public Float deserialize(final JsonParser parser, final DeserializationContext context) throws IOException {
        if (parser.currentToken() != null && parser.currentToken().isNumeric()) {
            return parser.getFloatValue();
        }
        return GameDataManager.parseAngleValue(parser.getValueAsString());
    }
}
