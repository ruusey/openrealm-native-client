package com.openrealm.net;

import java.io.DataOutputStream;

import com.openrealm.net.core.PacketId;

@PacketId(packetId=(byte)-1)
public class BlankPacket extends Packet {

    public BlankPacket(byte packetId, byte[] data) {
        super(packetId, data);
    }
    @Override
    public void readData(byte[] data) throws Exception {
    }

    @Override
    public int serializeWrite(DataOutputStream stream) throws Exception {
       return 0;
    }
}
