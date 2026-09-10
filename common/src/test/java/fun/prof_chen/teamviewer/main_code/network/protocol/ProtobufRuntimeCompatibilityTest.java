package fun.prof_chen.teamviewer.main_code.network.protocol;

import fun.prof_chen.teamviewer.main_code.network.proto.WireEnvelope;
import fun.prof_chen.teamviewer.main_code.network.proto.PlayerDirectoryEntry;
import fun.prof_chen.teamviewer.main_code.network.proto.PlayerDirectoryLookupChunk;
import fun.prof_chen.teamviewer.main_code.network.proto.PlayerDirectoryLookupResult;
import fun.prof_chen.teamviewer.main_code.network.proto.PlayerIdentityRecord;
import fun.prof_chen.teamviewer.main_code.network.proto.PlayerRelationKind;
import fun.prof_chen.teamviewer.main_code.network.proto.PlayerRelationQueryChunk;
import fun.prof_chen.teamviewer.main_code.network.proto.PlayerRelationResult;
import fun.prof_chen.teamviewer.main_code.network.proto.PlayerData;
import fun.prof_chen.teamviewer.main_code.network.proto.PlayerDelta;
import fun.prof_chen.teamviewer.main_code.network.proto.PlayerUpsert;
import fun.prof_chen.teamviewer.main_code.network.proto.PlayerPositionSourceKind;
import fun.prof_chen.teamviewer.main_code.network.proto.Patch;
import fun.prof_chen.teamviewer.main_code.network.proto.SnapshotFull;
import fun.prof_chen.teamviewer.main_code.network.proto.BattleChunkCoord;
import fun.prof_chen.teamviewer.main_code.network.proto.BattleChunkEntry;
import fun.prof_chen.teamviewer.main_code.network.proto.BattleChunkPatchScope;
import fun.prof_chen.teamviewer.main_code.network.proto.BattleChunkRef;
import fun.prof_chen.teamviewer.main_code.network.proto.BattleChunkUpsert;
import fun.prof_chen.teamviewer.main_code.network.proto.BattleChunkValue;
import fun.prof_chen.teamviewer.main_code.network.proto.RefreshRequest;
import fun.prof_chen.teamviewer.main_code.model.BattleChunkRefData;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtobufRuntimeCompatibilityTest {
    @Test
    void generatedMessagesLoadWithPackagedRuntime() {
        WireEnvelope envelope = assertDoesNotThrow(WireEnvelope::getDefaultInstance);
        assertEquals(WireEnvelope.PayloadCase.PAYLOAD_NOT_SET, envelope.getPayloadCase());
    }

    @Test
    void roundTripsExternalDirectoryAndRelationQueries() throws Exception {
        ProtobufMessageCodec codec = new ProtobufMessageCodec();
        ProtocolPackets.PlayerDirectoryLookupRequestPacket lookupRequest =
                new ProtocolPackets.PlayerDirectoryLookupRequestPacket();
        lookupRequest.requestId = "directory-request";
        lookupRequest.playerIds = List.of("00000000-0000-0000-0000-000000000001");
        lookupRequest.maxChunkEntries = 64;

        WireEnvelope encodedLookupRequest = WireEnvelope.parseFrom(codec.encode(lookupRequest));
        assertEquals(WireEnvelope.PayloadCase.PLAYER_DIRECTORY_LOOKUP_REQUEST,
                encodedLookupRequest.getPayloadCase());
        assertEquals(64, encodedLookupRequest.getPlayerDirectoryLookupRequest().getMaxChunkEntries());
        WireEnvelope lookupEnvelope = WireEnvelope.newBuilder()
                .setPlayerDirectoryLookupChunk(PlayerDirectoryLookupChunk.newBuilder()
                        .setRequestId("directory-request")
                        .addResults(PlayerDirectoryLookupResult.newBuilder()
                                .setSelectorIndex(0)
                                .addEntries(PlayerDirectoryEntry.newBuilder()
                                        .setPlayer(PlayerIdentityRecord.newBuilder()
                                                .setPlayerId("00000000-0000-0000-0000-000000000001")
                                                .setUuid("00000000-0000-0000-0000-000000000001")
                                                .setName("Alpha")
                                                .addAliases("Old")
                                                .addAffiliationIds("town-a"))
                                        .setDatasetId("towns")
                                        .setObservedAtUtcMs(42L)))
                        .setChunkIndex(0)
                        .setChunkCount(1)
                        .setFinal(true))
                .build();
        ProtocolPackets.DecodedInboundMessage decodedLookup =
                codec.decode(lookupEnvelope.toByteArray());
        assertEquals("player_directory_lookup_chunk", decodedLookup.type);
        assertEquals("directory-request",
                ((ProtocolPackets.PlayerDirectoryLookupChunkInboundPacket) decodedLookup.packet).requestId);

        ProtocolPackets.PlayerRelationQueryRequestPacket relationRequest =
                new ProtocolPackets.PlayerRelationQueryRequestPacket();
        relationRequest.requestId = "relation-request";
        relationRequest.subjectPlayerId = "00000000-0000-0000-0000-000000000002";
        relationRequest.targetPlayerIds = List.of("00000000-0000-0000-0000-000000000001");
        relationRequest.maxChunkEntries = 64;

        WireEnvelope relationRequestEnvelope = WireEnvelope.parseFrom(codec.encode(relationRequest));
        assertEquals(WireEnvelope.PayloadCase.PLAYER_RELATION_QUERY_REQUEST,
                relationRequestEnvelope.getPayloadCase());
        assertEquals(64, relationRequestEnvelope.getPlayerRelationQueryRequest().getMaxChunkEntries());

        WireEnvelope relationEnvelope = WireEnvelope.newBuilder()
                .setPlayerRelationQueryChunk(PlayerRelationQueryChunk.newBuilder()
                        .setRequestId("relation-request")
                        .addResults(PlayerRelationResult.newBuilder()
                                .setTarget(PlayerDirectoryEntry.newBuilder()
                                        .setPlayer(PlayerIdentityRecord.newBuilder()
                                                .setPlayerId("00000000-0000-0000-0000-000000000001")
                                                .setUuid("00000000-0000-0000-0000-000000000001")
                                                .setName("Alpha"))
                                        .setDatasetId("towns"))
                                .setRelation(PlayerRelationKind.PLAYER_RELATION_KIND_FRIENDLY))
                        .setChunkIndex(0)
                        .setChunkCount(1)
                        .setFinal(true))
                .build();
        ProtocolPackets.DecodedInboundMessage decodedRelation =
                codec.decode(relationEnvelope.toByteArray());
        assertEquals("player_relation_query_chunk", decodedRelation.type);
        Map<?, ?> result = (Map<?, ?>)
                ((ProtocolPackets.PlayerRelationQueryChunkInboundPacket) decodedRelation.packet).results.get(0);
        assertEquals("PLAYER_RELATION_KIND_FRIENDLY", result.get("relation"));
    }

    @Test
    void decodesHandshakeRelationshipCapabilitiesAndReportPolicy() {
        WireEnvelope envelope = WireEnvelope.newBuilder()
                .setHandshakeAck(fun.prof_chen.teamviewer.main_code.network.proto.HandshakeAck.newBuilder()
                        .setReady(true)
                        .setRelationshipQuery(fun.prof_chen.teamviewer.main_code.network.proto.RelationshipQueryCapabilities.newBuilder()
                                .setSupported(true)
                                .setMaxSelectors(128)
                                .setMaxChunkEntries(96))
                        .setReportPolicy(fun.prof_chen.teamviewer.main_code.network.proto.PlayerReportPolicy.newBuilder()
                                .addRecommendations(fun.prof_chen.teamviewer.main_code.network.proto.ReportRecommendation.newBuilder()
                                        .setScope(fun.prof_chen.teamviewer.main_code.network.proto.ReportScope.REPORT_SCOPE_TAB)
                                        .setMode(fun.prof_chen.teamviewer.main_code.network.proto.ReportRecommendationMode.REPORT_RECOMMENDATION_MODE_SUPPRESS))))
                .build();

        ProtocolPackets.DecodedInboundMessage decoded =
                new ProtobufMessageCodec().decode(envelope.toByteArray());
        ProtocolPackets.HandshakeAckInboundPacket handshake =
                (ProtocolPackets.HandshakeAckInboundPacket) decoded.packet;
        assertEquals(Boolean.TRUE, handshake.relationshipQuery.get("supported"));
        assertEquals(128, handshake.relationshipQuery.get("maxSelectors"));
        assertEquals("REPORT_RECOMMENDATION_MODE_SUPPRESS",
                ((List<?>) handshake.reportPolicy.get("recommendations")).stream()
                        .map(value -> ((Map<?, ?>) value).get("mode"))
                        .findFirst().orElse(null));
    }

    @Test
    void decodesPlayerPositionSourceMetadata() {
        UUID playerId = UUID.randomUUID();
        PlayerData player = PlayerData.newBuilder()
                .setX(1.0).setY(64.0).setZ(2.0)
                .setDimension("minecraft:overworld")
                .setPositionSourceId("squaremap-source")
                .setPositionSourceKind(PlayerPositionSourceKind.PLAYER_POSITION_SOURCE_KIND_EXTERNAL_SOURCE)
                .setPositionSourceDisplayName("Squaremap")
                .setPositionResolution(1.0)
                .build();
        WireEnvelope envelope = WireEnvelope.newBuilder()
                .setSnapshotFull(SnapshotFull.newBuilder().putPlayers(playerId.toString(), player))
                .build();

        ProtocolPackets.DecodedInboundMessage decoded =
                new ProtobufMessageCodec().decode(envelope.toByteArray());
        ProtocolPackets.SnapshotFullInboundPacket snapshot =
                (ProtocolPackets.SnapshotFullInboundPacket) decoded.packet;
        Map<?, ?> data = (Map<?, ?>) snapshot.players.get(playerId.toString());

        assertEquals("squaremap-source", data.get("positionSourceId"));
        assertEquals("PLAYER_POSITION_SOURCE_KIND_EXTERNAL_SOURCE", data.get("positionSourceKind"));
        assertEquals("Squaremap", data.get("positionSourceDisplayName"));
        assertEquals(1.0, data.get("positionResolution"));
    }

    @Test
    void decodesClearFieldsAsExplicitNulls() {
        UUID playerId = UUID.randomUUID();
        WireEnvelope envelope = WireEnvelope.newBuilder()
                .setPatch(Patch.newBuilder().setPlayers(
                        fun.prof_chen.teamviewer.main_code.network.proto.PlayerPatchScope.newBuilder()
                                .addUpsert(PlayerUpsert.newBuilder()
                                        .setId(playerId.toString())
                                        .setData(PlayerDelta.newBuilder().setX(2.0))
                                        .addClearFields("positionSourceId"))))
                .build();

        ProtocolPackets.DecodedInboundMessage decoded =
                new ProtobufMessageCodec().decode(envelope.toByteArray());
        ProtocolPackets.PatchInboundPacket patch =
                (ProtocolPackets.PatchInboundPacket) decoded.packet;
        Map<?, ?> scope = (Map<?, ?>) patch.players.get("upsert");
        Map<?, ?> data = (Map<?, ?>) scope.get(playerId.toString());

        assertEquals(2.0, data.get("x"));
        assertTrue(data.containsKey("positionSourceId"));
        assertEquals(null, data.get("positionSourceId"));
    }

    @Test
    void battleChunksStayStructuredAcrossSnapshotPatchRefreshAndKeepalive() throws Exception {
        BattleChunkRef firstRef = BattleChunkRef.newBuilder()
                .setDimension("minecraft:overworld")
                .setCoord(BattleChunkCoord.newBuilder().setChunkX(-12).setChunkZ(34))
                .build();
        BattleChunkValue value = BattleChunkValue.newBuilder()
                .setColorRaw("#112233")
                .setColorMode("raw_observed")
                .setMode("simmc")
                .build();
        ProtobufMessageCodec codec = new ProtobufMessageCodec();

        WireEnvelope snapshotEnvelope = WireEnvelope.newBuilder()
                .setSnapshotFull(SnapshotFull.newBuilder().addBattleChunks(
                        BattleChunkEntry.newBuilder().setRef(firstRef).setData(value)))
                .build();
        ProtocolPackets.SnapshotFullInboundPacket snapshot =
                (ProtocolPackets.SnapshotFullInboundPacket) codec.decode(snapshotEnvelope.toByteArray()).packet;
        assertEquals(1, snapshot.battleChunks.size());
        assertEquals(new BattleChunkRefData("minecraft:overworld", -12, 34),
                snapshot.battleChunks.get(0).ref());
        assertFalse(snapshot.battleChunks.get(0).data().containsKey("dimension"));
        assertFalse(snapshot.battleChunks.get(0).data().containsKey("chunkX"));
        assertFalse(snapshot.battleChunks.get(0).data().containsKey("chunkZ"));

        BattleChunkRef deleteRef = BattleChunkRef.newBuilder()
                .setDimension("minecraft:the_nether")
                .setCoord(BattleChunkCoord.newBuilder().setChunkX(7).setChunkZ(-8))
                .build();
        WireEnvelope patchEnvelope = WireEnvelope.newBuilder()
                .setPatch(Patch.newBuilder().setBattleChunks(BattleChunkPatchScope.newBuilder()
                        .addUpsert(BattleChunkUpsert.newBuilder()
                                .setRef(firstRef).setData(value).addClearFields("colorNote"))
                        .addDelete(deleteRef)))
                .build();
        ProtocolPackets.PatchInboundPacket patch =
                (ProtocolPackets.PatchInboundPacket) codec.decode(patchEnvelope.toByteArray()).packet;
        assertEquals(new BattleChunkRefData("minecraft:overworld", -12, 34),
                patch.battleChunks.upsert().get(0).ref());
        assertEquals(List.of("colorNote"), patch.battleChunks.upsert().get(0).clearFields());
        assertEquals(List.of(new BattleChunkRefData("minecraft:the_nether", 7, -8)),
                patch.battleChunks.delete());

        WireEnvelope refreshEnvelope = WireEnvelope.newBuilder()
                .setRefreshRequest(RefreshRequest.newBuilder().addBattleChunks(deleteRef))
                .build();
        ProtocolPackets.RefreshReqInboundPacket refresh =
                (ProtocolPackets.RefreshReqInboundPacket) codec.decode(refreshEnvelope.toByteArray()).packet;
        assertEquals(List.of(new BattleChunkRefData("minecraft:the_nether", 7, -8)),
                refresh.battleChunks);

        ProtocolPackets.StateKeepalivePacket keepalive = new ProtocolPackets.StateKeepalivePacket();
        keepalive.submitPlayerId = UuidBinaryCodec.toBytes(UUID.randomUUID());
        keepalive.players = List.of();
        keepalive.entities = List.of();
        keepalive.battleChunks = List.of(
                new BattleChunkRefData("minecraft:overworld", -12, 34),
                new BattleChunkRefData("minecraft:the_nether", 7, -8));
        WireEnvelope encoded = WireEnvelope.parseFrom(codec.encode(keepalive));
        assertEquals(2, encoded.getPlayerReportBundle().getStateKeepalive().getBattleChunksCount());
        assertEquals("minecraft:the_nether", encoded.getPlayerReportBundle()
                .getStateKeepalive().getBattleChunks(1).getDimension());
    }

    @Test
    void handshakeDeclaresDatagramChannelsAndParsesDownlinkAck() throws Exception {
        ProtobufMessageCodec codec = new ProtobufMessageCodec();
        ProtocolPackets.HandshakePacket handshake = new ProtocolPackets.HandshakePacket();
        handshake.declaresUplinkMovementDatagram = true;
        handshake.declaresDownlinkMovementDatagram = true;

        WireEnvelope encoded = WireEnvelope.parseFrom(codec.encode(handshake));
        var request = encoded.getPlayerHandshakeRequest();
        assertEquals(List.of(fun.prof_chen.teamviewer.main_code.network.proto.UnreliableChannel.UNRELIABLE_CHANNEL_MOVEMENT),
                request.getUnreliableChannelsList());
        assertEquals(List.of(fun.prof_chen.teamviewer.main_code.network.proto.UnreliableChannel.UNRELIABLE_CHANNEL_MOVEMENT),
                request.getAcceptsChannelsList());

        // 未声明时两通道都不写(缺省字段不上线)
        WireEnvelope bare = WireEnvelope.parseFrom(codec.encode(new ProtocolPackets.HandshakePacket()));
        assertTrue(bare.getPlayerHandshakeRequest().getUnreliableChannelsList().isEmpty());
        assertTrue(bare.getPlayerHandshakeRequest().getAcceptsChannelsList().isEmpty());

        WireEnvelope ackEnvelope = WireEnvelope.newBuilder()
                .setHandshakeAck(fun.prof_chen.teamviewer.main_code.network.proto.HandshakeAck.newBuilder()
                        .setReady(true)
                        .addUnreliableChannelsAccepted(
                                fun.prof_chen.teamviewer.main_code.network.proto.UnreliableChannel.UNRELIABLE_CHANNEL_MOVEMENT)
                        .addDownlinkChannelsAccepted(
                                fun.prof_chen.teamviewer.main_code.network.proto.UnreliableChannel.UNRELIABLE_CHANNEL_MOVEMENT))
                .build();
        ProtocolPackets.HandshakeAckInboundPacket ack =
                (ProtocolPackets.HandshakeAckInboundPacket) codec.decode(ackEnvelope.toByteArray()).packet;
        assertEquals(Boolean.TRUE, ack.uplinkMovementDatagramAccepted);
        assertEquals(Boolean.TRUE, ack.downlinkMovementDatagramAccepted);
    }

    @Test
    void typedEntityPatchWritesOnlyMaskedFields() throws Exception {
        UUID submit = UUID.randomUUID();
        UUID entity = UUID.randomUUID();
        EntityPatchView patch = new EntityPatchView() {
            @Override
            public int upsertCount() { return 1; }
            @Override
            public UUID upsertId(int index) { return entity; }
            @Override
            public int fieldMask(int index) { return X | DIMENSION | TYPE | WIDTH; }
            @Override
            public double x(int index) { return 12.5; }
            @Override
            public double y(int index) { return 99; }
            @Override
            public double z(int index) { return 0; }
            @Override
            public double vx(int index) { return 0; }
            @Override
            public double vy(int index) { return 0; }
            @Override
            public double vz(int index) { return 0; }
            @Override
            public String dimension(int index) { return "minecraft:overworld"; }
            @Override
            public String entityType(int index) { return "minecraft:zombie"; }
            @Override
            public String entityName(int index) { return "ignored"; }
            @Override
            public float width(int index) { return 0.6f; }
            @Override
            public float height(int index) { return 1.95f; }
            @Override
            public int deleteCount() { return 0; }
            @Override
            public UUID deleteId(int index) { throw new IndexOutOfBoundsException(index); }
        };

        WireEnvelope envelope = WireEnvelope.parseFrom(
                new ProtobufMessageCodec().encodeEntityPatch(submit, patch));
        var bundle = envelope.getPlayerReportBundle();
        var upsert = bundle.getEntitiesPatch().getUpsert(0);
        assertEquals(submit.toString(), bundle.getSubmitPlayerId());
        assertEquals(entity.toString(), upsert.getId());
        assertEquals(12.5, upsert.getData().getX());
        assertTrue(upsert.getData().hasX());
        assertFalse(upsert.getData().hasY());
        assertEquals("minecraft:overworld", upsert.getData().getDimension());
        assertEquals("minecraft:zombie", upsert.getData().getEntityType());
        assertFalse(upsert.getData().hasEntityName());
        assertTrue(upsert.getData().hasWidth());
        assertFalse(upsert.getData().hasHeight());
    }
}
