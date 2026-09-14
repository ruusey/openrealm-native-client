package com.openrealm.game.data;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

// Accepts a radian angle as a plain number or a "{{PI/2}}" placeholder string;
// without it a single placeholder-valued angle aborts the whole array parse.
public class RadianAngleDeserializer extends JsonDeserializer<Float> {

    @Override
    public Float deserialize(final JsonParser parser, final DeserializationContext context) throws IOException {
        if (parser.currentToken() != null && parser.currentToken().isNumeric()) {
            return parser.getFloatValue();
        }
        return GameDataManager.parseAngleValue(parser.getValueAsString());
    }
}
