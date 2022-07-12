package pt.ist.socialsoftware.mono2micro.decomposition.dto.accessesSciPyDtos;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import pt.ist.socialsoftware.mono2micro.utils.deserializers.AccessDtoDeserializer;
import pt.ist.socialsoftware.mono2micro.utils.serializers.AccessDtoSerializer;

import java.util.Objects;

@JsonDeserialize(using = AccessDtoDeserializer.class)
@JsonSerialize(using = AccessDtoSerializer.class)
public class AccessDto extends ReducedTraceElementDto {
    private short entityID;
    private byte mode; // "R" -> 1, "W" -> 2

    public AccessDto() {}

    public short getEntityID() { return entityID; }
    public void setEntityID(short entityID) { this.entityID = entityID; }

    public byte getMode() { return mode; }
    public void setMode(byte mode) { this.mode = mode; }

    @Override
	public boolean equals(final Object other) {
        if (other instanceof AccessDto) {
            AccessDto that = (AccessDto) other;
            return this.entityID == that.entityID && this.mode == that.mode;
        }
        
        return false;
    }

    @Override
    public String toString() {
        if (occurrences < 2)
            return "[" + entityID + ',' + mode + ']';

        return "[" + entityID + ',' + mode + ',' + occurrences + ']';
    }

    @Override
    public int hashCode() {
        return Objects.hash(entityID, mode);
    }
}
