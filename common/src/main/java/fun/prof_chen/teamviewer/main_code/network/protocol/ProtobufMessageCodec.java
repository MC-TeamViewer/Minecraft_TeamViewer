package fun.prof_chen.teamviewer.main_code.network.protocol;

import fun.prof_chen.teamviewer.main_code.model.BattleChunkRefData;
import fun.prof_chen.teamviewer.main_code.network.proto.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProtobufMessageCodec implements MessageCodec {
	@Override
	public byte[] encode(Object packet) {
		try {
			WireEnvelope.Builder envelope = WireEnvelope.newBuilder().setChannel(WireChannel.WIRE_CHANNEL_PLAYER);
			if (packet instanceof ProtocolPackets.HandshakePacket handshake) {
				envelope.setPlayerHandshakeRequest(buildPlayerHandshake(handshake));
				return envelope.build().toByteArray();
			}
			if (packet instanceof ProtocolPackets.PlayersPatchPacket playersPatch) {
				envelope.setPlayerReportBundle(
						PlayerReportBundle.newBuilder()
								.setSubmitPlayerId(uuidString(playersPatch.submitPlayerId))
								.setPlayersPatch(buildPlayerPatchScope(playersPatch.upsert, playersPatch.delete))
								.build()
				);
				return envelope.build().toByteArray();
			}
			if (packet instanceof ProtocolPackets.EntitiesPatchPacket entitiesPatch) {
				envelope.setPlayerReportBundle(
						PlayerReportBundle.newBuilder()
								.setSubmitPlayerId(uuidString(entitiesPatch.submitPlayerId))
								.setEntitiesPatch(buildEntityPatchScope(entitiesPatch.upsert, entitiesPatch.delete))
								.build()
				);
				return envelope.build().toByteArray();
			}
			if (packet instanceof ProtocolPackets.WaypointsUpdatePacket waypointsUpdate) {
				envelope.setPlayerReportBundle(
						PlayerReportBundle.newBuilder()
								.setSubmitPlayerId(uuidString(waypointsUpdate.submitPlayerId))
								.setWaypointsReplace(buildWaypointsReplace(waypointsUpdate.waypoints))
								.build()
				);
				return envelope.build().toByteArray();
			}
			if (packet instanceof ProtocolPackets.SourceStateClearPacket stateClear) {
				envelope.setPlayerReportBundle(
						PlayerReportBundle.newBuilder()
								.setSubmitPlayerId(uuidString(stateClear.submitPlayerId))
								.setSourceStateClear(
										SourceStateClear.newBuilder().addAllScopes(cleanStringList(stateClear.scopes)).build()
								)
								.build()
				);
				return envelope.build().toByteArray();
			}
			if (packet instanceof ProtocolPackets.TabPlayersPatchPacket tabPlayersPatch) {
				envelope.setPlayerReportBundle(
						PlayerReportBundle.newBuilder()
								.setSubmitPlayerId(uuidString(tabPlayersPatch.submitPlayerId))
								.setTabPlayersPatch(buildTabPlayersPatchScope(tabPlayersPatch.upsert, tabPlayersPatch.delete))
								.build()
				);
				return envelope.build().toByteArray();
			}
			if (packet instanceof ProtocolPackets.WaypointsDeletePacket waypointsDelete) {
				envelope.setPlayerReportBundle(
						PlayerReportBundle.newBuilder()
								.setSubmitPlayerId(uuidString(waypointsDelete.submitPlayerId))
								.setWaypointsDelete(
										WaypointsDelete.newBuilder()
												.addAllWaypointIds(cleanStringList(waypointsDelete.waypointIds))
												.build()
								)
								.build()
				);
				return envelope.build().toByteArray();
			}
			if (packet instanceof ProtocolPackets.BattleMapObservationPacket observation) {
				envelope.setPlayerReportBundle(
						PlayerReportBundle.newBuilder()
								.setSubmitPlayerId(uuidString(observation.submitPlayerId))
								.setBattleMapObservation(buildBattleMapObservation(observation))
								.build()
				);
				return envelope.build().toByteArray();
			}
			if (packet instanceof ProtocolPackets.WaypointsEntityDeathCancelPacket deathCancel) {
				envelope.setPlayerReportBundle(
						PlayerReportBundle.newBuilder()
								.setSubmitPlayerId(uuidString(deathCancel.submitPlayerId))
								.setWaypointsEntityDeathCancel(
										WaypointsEntityDeathCancel.newBuilder()
												.addAllTargetEntityIds(cleanStringList(deathCancel.targetEntityIds))
												.build()
								)
								.build()
				);
				return envelope.build().toByteArray();
			}
			if (packet instanceof ProtocolPackets.ResyncReqPacket resyncReq) {
				ResyncRequest.Builder builder = ResyncRequest.newBuilder();
				String reason = normalizeText(resyncReq.reason);
				if (reason != null) {
					builder.setReason(reason);
				}
				envelope.setResyncRequest(builder.build());
				return envelope.build().toByteArray();
			}
			if (packet instanceof ProtocolPackets.StateKeepalivePacket keepalive) {
				envelope.setPlayerReportBundle(
						PlayerReportBundle.newBuilder()
								.setSubmitPlayerId(uuidString(keepalive.submitPlayerId))
								.setStateKeepalive(
										StateKeepalive.newBuilder()
												.addAllPlayers(cleanStringList(keepalive.players))
												.addAllEntities(cleanStringList(keepalive.entities))
												.addAllBattleChunks(buildBattleChunkRefs(keepalive.battleChunks))
												.build()
								)
								.build()
				);
				return envelope.build().toByteArray();
			}
			if (packet instanceof ProtocolPackets.TabHistorySubscribePacket subscribe) {
				TabHistorySubscribeRequest.Builder builder = TabHistorySubscribeRequest.newBuilder()
						.setEnabled(subscribe.enabled);
				if (subscribe.knownRevision != null) builder.setKnownRevision(subscribe.knownRevision);
				if (subscribe.knownDigestSha256 != null) {
					builder.setKnownDigestSha256(com.google.protobuf.ByteString.copyFrom(subscribe.knownDigestSha256));
				}
				envelope.setTabHistorySubscribeRequest(builder.build());
				return envelope.build().toByteArray();
			}
			if (packet instanceof ProtocolPackets.TabHistorySyncRequestPacket sync) {
				TabHistorySyncRequest.Builder builder = TabHistorySyncRequest.newBuilder()
						.setRequestId(defaultString(sync.requestId))
						.setPreferredMode(tabHistorySyncMode(sync.preferredMode))
						.setAllowFullFallback(sync.allowFullFallback);
				if (sync.baseRevision != null) builder.setBaseRevision(sync.baseRevision);
				if (sync.baseDigestSha256 != null) {
					builder.setBaseDigestSha256(com.google.protobuf.ByteString.copyFrom(sync.baseDigestSha256));
				}
				setOptionalInt(builder::setMaxChunkEntries, sync.maxChunkEntries);
				envelope.setTabHistorySyncRequest(builder.build());
				return envelope.build().toByteArray();
			}
			if (packet instanceof ProtocolPackets.TabHistoryLookupRequestPacket lookup) {
				TabHistoryLookupRequest.Builder builder = TabHistoryLookupRequest.newBuilder()
						.setRequestId(defaultString(lookup.requestId));
				for (String uuid : cleanStringList(lookup.uuids)) {
					builder.addSelectors(TabHistoryLookupSelector.newBuilder().setUuid(uuid).build());
				}
				for (String name : cleanStringList(lookup.names)) {
					builder.addSelectors(TabHistoryLookupSelector.newBuilder().setName(name).build());
				}
				setOptionalInt(builder::setMaxChunkEntries, lookup.maxChunkEntries);
				envelope.setTabHistoryLookupRequest(builder.build());
				return envelope.build().toByteArray();
			}
			if (packet instanceof ProtocolPackets.PlayerDirectoryLookupRequestPacket lookup) {
				PlayerDirectoryLookupRequest.Builder builder = PlayerDirectoryLookupRequest.newBuilder()
						.setRequestId(defaultString(lookup.requestId));
				for (String playerId : cleanStringList(lookup.playerIds)) {
					builder.addSelectors(PlayerSelector.newBuilder().setPlayerId(playerId).build());
				}
				setOptionalInt(builder::setMaxChunkEntries, lookup.maxChunkEntries);
				envelope.setPlayerDirectoryLookupRequest(builder.build());
				return envelope.build().toByteArray();
			}
			if (packet instanceof ProtocolPackets.PlayerRelationQueryRequestPacket relation) {
				PlayerRelationQueryRequest.Builder builder = PlayerRelationQueryRequest.newBuilder()
						.setRequestId(defaultString(relation.requestId))
						.setSubject(PlayerSelector.newBuilder()
								.setPlayerId(defaultString(relation.subjectPlayerId))
								.build());
				for (String playerId : cleanStringList(relation.targetPlayerIds)) {
					builder.addTargets(PlayerSelector.newBuilder().setPlayerId(playerId).build());
				}
				setOptionalInt(builder::setMaxChunkEntries, relation.maxChunkEntries);
				envelope.setPlayerRelationQueryRequest(builder.build());
				return envelope.build().toByteArray();
			}
			throw new IllegalArgumentException("Unsupported protobuf packet type: " + packet);
		} catch (Exception e) {
			throw new IllegalArgumentException("Failed to encode protobuf payload", e);
		}
	}

	@Override
	public byte[] encodeEntityPatch(java.util.UUID submitPlayerId, EntityPatchView patch) {
		if (submitPlayerId == null || patch == null) {
			throw new IllegalArgumentException("Entity patch requires a submit player and patch");
		}
		try {
			EntityPatchScope.Builder scope = EntityPatchScope.newBuilder();
			for (int index = 0; index < patch.upsertCount(); index++) {
				EntityDelta.Builder delta = EntityDelta.newBuilder();
				int mask = patch.fieldMask(index);
				if ((mask & EntityPatchView.X) != 0) delta.setX(patch.x(index));
				if ((mask & EntityPatchView.Y) != 0) delta.setY(patch.y(index));
				if ((mask & EntityPatchView.Z) != 0) delta.setZ(patch.z(index));
				if ((mask & EntityPatchView.VX) != 0) delta.setVx(patch.vx(index));
				if ((mask & EntityPatchView.VY) != 0) delta.setVy(patch.vy(index));
				if ((mask & EntityPatchView.VZ) != 0) delta.setVz(patch.vz(index));
				if ((mask & EntityPatchView.DIMENSION) != 0 && patch.dimension(index) != null) {
					delta.setDimension(patch.dimension(index));
				}
				if ((mask & EntityPatchView.TYPE) != 0 && patch.entityType(index) != null) {
					delta.setEntityType(patch.entityType(index));
				}
				if ((mask & EntityPatchView.NAME) != 0 && patch.entityName(index) != null) {
					delta.setEntityName(patch.entityName(index));
				}
				if ((mask & EntityPatchView.WIDTH) != 0) delta.setWidth(patch.width(index));
				if ((mask & EntityPatchView.HEIGHT) != 0) delta.setHeight(patch.height(index));
				scope.addUpsert(EntityUpsert.newBuilder()
						.setId(patch.upsertId(index).toString())
						.setData(delta.build())
						.build());
			}
			for (int index = 0; index < patch.deleteCount(); index++) {
				scope.addDelete(patch.deleteId(index).toString());
			}
			WireEnvelope envelope = WireEnvelope.newBuilder()
					.setChannel(WireChannel.WIRE_CHANNEL_PLAYER)
					.setPlayerReportBundle(PlayerReportBundle.newBuilder()
							.setSubmitPlayerId(submitPlayerId.toString())
							.setEntitiesPatch(scope.build())
							.build())
					.build();
			return envelope.toByteArray();
		} catch (Exception error) {
			throw new IllegalArgumentException("Failed to encode typed entity patch", error);
		}
	}

	@Override
	public ProtocolPackets.DecodedInboundMessage decode(byte[] payload) {
		try {
			WireEnvelope envelope = WireEnvelope.parseFrom(payload);
			return switch (envelope.getPayloadCase()) {
				case HANDSHAKE_ACK -> new ProtocolPackets.DecodedInboundMessage(
						"handshake_ack",
						decodeHandshakeAck(envelope.getHandshakeAck())
				);
				case SNAPSHOT_FULL -> new ProtocolPackets.DecodedInboundMessage(
						"snapshot_full",
						decodeSnapshotFull(envelope.getSnapshotFull())
				);
				case PATCH -> new ProtocolPackets.DecodedInboundMessage(
						"patch",
						decodePatch(envelope.getPatch())
				);
				case DIGEST -> new ProtocolPackets.DecodedInboundMessage(
						"digest",
						decodeDigest(envelope.getDigest())
				);
				case REFRESH_REQUEST -> new ProtocolPackets.DecodedInboundMessage(
						"refresh_req",
						decodeRefreshRequest(envelope.getRefreshRequest())
				);
				case REPORT_RATE_HINT -> new ProtocolPackets.DecodedInboundMessage(
						"report_rate_hint",
						decodeReportRateHint(envelope.getReportRateHint())
				);
				case TAB_HISTORY_DIGEST -> new ProtocolPackets.DecodedInboundMessage(
						"tab_history_digest", decodeTabHistoryDigest(envelope.getTabHistoryDigest()));
				case TAB_HISTORY_SYNC_CHUNK -> new ProtocolPackets.DecodedInboundMessage(
						"tab_history_sync_chunk", decodeTabHistorySyncChunk(envelope.getTabHistorySyncChunk()));
				case TAB_HISTORY_LOOKUP_CHUNK -> new ProtocolPackets.DecodedInboundMessage(
						"tab_history_lookup_chunk", decodeTabHistoryLookupChunk(envelope.getTabHistoryLookupChunk()));
				case PLAYER_DIRECTORY_LOOKUP_CHUNK -> new ProtocolPackets.DecodedInboundMessage(
						"player_directory_lookup_chunk",
						decodePlayerDirectoryLookupChunk(envelope.getPlayerDirectoryLookupChunk()));
				case PLAYER_RELATION_QUERY_CHUNK -> new ProtocolPackets.DecodedInboundMessage(
						"player_relation_query_chunk",
						decodePlayerRelationQueryChunk(envelope.getPlayerRelationQueryChunk()));
				default -> throw new IllegalArgumentException("Unsupported protobuf payload case: " + envelope.getPayloadCase());
			};
		} catch (Exception e) {
			throw new IllegalArgumentException("Failed to decode protobuf payload", e);
		}
	}

	private PlayerHandshakeRequest buildPlayerHandshake(ProtocolPackets.HandshakePacket packet) {
		PlayerHandshakeRequest.Builder builder = PlayerHandshakeRequest.newBuilder();
		builder.setNetworkProtocolVersion(defaultString(packet.networkProtocolVersion));
		builder.setMinimumCompatibleNetworkProtocolVersion(defaultString(packet.minimumCompatibleNetworkProtocolVersion));
		builder.setLocalProgramVersion(defaultString(packet.localProgramVersion));
		String submitPlayerId = uuidString(packet.submitPlayerId);
		if (submitPlayerId != null) {
			builder.setSubmitPlayerId(submitPlayerId);
		}
		setOptionalString(builder::setRoomCode, normalizeText(packet.roomCode));
		setOptionalInt(builder::setPreferredReportIntervalTicks, packet.preferredReportIntervalTicks);
		setOptionalInt(builder::setMinReportIntervalTicks, packet.minReportIntervalTicks);
		setOptionalInt(builder::setMaxReportIntervalTicks, packet.maxReportIntervalTicks);
		if (packet.declaresUplinkMovementDatagram) {
			builder.addUnreliableChannels(UnreliableChannel.UNRELIABLE_CHANNEL_MOVEMENT);
		}
		if (packet.declaresDownlinkMovementDatagram) {
			builder.addAcceptsChannels(UnreliableChannel.UNRELIABLE_CHANNEL_MOVEMENT);
		}
		return builder.build();
	}

	private PlayerPatchScope buildPlayerPatchScope(Map<String, Map<String, Object>> upsert, List<String> delete) {
		PlayerPatchScope.Builder builder = PlayerPatchScope.newBuilder();
		for (Map.Entry<String, Map<String, Object>> entry : safeMap(upsert).entrySet()) {
			String id = normalizeText(entry.getKey());
			if (id == null) {
				continue;
			}
			PlayerUpsert.Builder upsertBuilder = PlayerUpsert.newBuilder().setId(id);
			upsertBuilder.setData(buildPlayerDelta(entry.getValue()));
			builder.addUpsert(upsertBuilder.build());
		}
		builder.addAllDelete(cleanStringList(delete));
		return builder.build();
	}

	private EntityPatchScope buildEntityPatchScope(Map<String, Map<String, Object>> upsert, List<String> delete) {
		EntityPatchScope.Builder builder = EntityPatchScope.newBuilder();
		for (Map.Entry<String, Map<String, Object>> entry : safeMap(upsert).entrySet()) {
			String id = normalizeText(entry.getKey());
			if (id == null) {
				continue;
			}
			EntityUpsert.Builder upsertBuilder = EntityUpsert.newBuilder().setId(id);
			upsertBuilder.setData(buildEntityDelta(entry.getValue()));
			builder.addUpsert(upsertBuilder.build());
		}
		builder.addAllDelete(cleanStringList(delete));
		return builder.build();
	}

	private WaypointsReplace buildWaypointsReplace(Map<String, Map<String, Object>> waypoints) {
		WaypointsReplace.Builder builder = WaypointsReplace.newBuilder();
		for (Map.Entry<String, Map<String, Object>> entry : safeMap(waypoints).entrySet()) {
			String id = normalizeText(entry.getKey());
			if (id == null) {
				continue;
			}
			builder.putWaypoints(id, buildWaypointData(entry.getValue()));
		}
		return builder.build();
	}

	private TabPlayersPatchScope buildTabPlayersPatchScope(Map<String, Map<String, Object>> upsert, List<String> delete) {
		TabPlayersPatchScope.Builder builder = TabPlayersPatchScope.newBuilder();
		for (Map.Entry<String, Map<String, Object>> entry : safeMap(upsert).entrySet()) {
			String id = normalizeText(entry.getKey());
			if (id == null) {
				continue;
			}
			builder.addUpsert(
					TabPlayerUpsert.newBuilder()
							.setKey(id)
							.setData(buildTabPlayerEntry(entry.getValue()))
							.build()
			);
		}
		builder.addAllDelete(cleanStringList(delete));
		return builder.build();
	}

	private BattleMapObservation buildBattleMapObservation(ProtocolPackets.BattleMapObservationPacket packet) {
		BattleMapObservation.Builder builder = BattleMapObservation.newBuilder();
		setOptionalString(builder::setMode, normalizeText(packet.mode));
		builder.setDimension(defaultString(packet.dimension));
		builder.setMapSize(defaultInt(packet.mapSize));
		builder.setAnchorRow(defaultInt(packet.anchorRow));
		builder.setAnchorCol(defaultInt(packet.anchorCol));
		builder.setSnapshotObservedAt(defaultLong(packet.snapshotObservedAt));
		builder.setParsedAt(defaultLong(packet.parsedAt));
		for (Map<String, Object> item : safeListOfMaps(packet.candidates)) {
			BattleMapObservationCandidate.Builder candidate = BattleMapObservationCandidate.newBuilder();
			setOptionalInt(candidate::setBaseChunkX, toIntegerOrNull(item.get("baseChunkX")));
			setOptionalInt(candidate::setBaseChunkZ, toIntegerOrNull(item.get("baseChunkZ")));
			setOptionalLong(candidate::setPositionSampledAt, toLongOrNull(item.get("positionSampledAt")));
			setOptionalString(candidate::setSource, normalizeText(item.get("source")));
			builder.addCandidates(candidate.build());
		}
		for (Map<String, Object> item : safeListOfMaps(packet.cells)) {
			BattleMapObservationCell.Builder cell = BattleMapObservationCell.newBuilder();
			setOptionalInt(cell::setRelChunkX, toIntegerOrNull(item.get("relChunkX")));
			setOptionalInt(cell::setRelChunkZ, toIntegerOrNull(item.get("relChunkZ")));
			setOptionalString(cell::setSymbol, normalizeText(item.get("symbol")));
			String colorRaw = normalizeText(item.get("colorRaw"));
			cell.setColorRaw(colorRaw == null ? "" : colorRaw);
			builder.addCells(cell.build());
		}
		return builder.build();
	}

	private PlayerDelta buildPlayerDelta(Map<String, Object> raw) {
		Map<String, Object> value = safeObjectMap(raw);
		PlayerDelta.Builder builder = PlayerDelta.newBuilder();
		setOptionalDouble(builder::setX, toDoubleOrNull(value.get("x")));
		setOptionalDouble(builder::setY, toDoubleOrNull(value.get("y")));
		setOptionalDouble(builder::setZ, toDoubleOrNull(value.get("z")));
		setOptionalDouble(builder::setVx, toDoubleOrNull(value.get("vx")));
		setOptionalDouble(builder::setVy, toDoubleOrNull(value.get("vy")));
		setOptionalDouble(builder::setVz, toDoubleOrNull(value.get("vz")));
		setOptionalString(builder::setDimension, normalizeText(value.get("dimension")));
		setOptionalString(builder::setPlayerName, normalizeText(value.get("playerName")));
		setOptionalString(builder::setPlayerUuid, normalizeText(value.get("playerUUID")));
		setOptionalDouble(builder::setHealth, toDoubleOrNull(value.get("health")));
		setOptionalDouble(builder::setMaxHealth, toDoubleOrNull(value.get("maxHealth")));
		setOptionalDouble(builder::setArmor, toDoubleOrNull(value.get("armor")));
		setOptionalBool(builder::setIsRiding, toBooleanOrNull(value.get("isRiding")));
		setOptionalDouble(builder::setWidth, toDoubleOrNull(value.get("width")));
		setOptionalDouble(builder::setHeight, toDoubleOrNull(value.get("height")));
		return builder.build();
	}

	private EntityDelta buildEntityDelta(Map<String, Object> raw) {
		Map<String, Object> value = safeObjectMap(raw);
		EntityDelta.Builder builder = EntityDelta.newBuilder();
		setOptionalDouble(builder::setX, toDoubleOrNull(value.get("x")));
		setOptionalDouble(builder::setY, toDoubleOrNull(value.get("y")));
		setOptionalDouble(builder::setZ, toDoubleOrNull(value.get("z")));
		setOptionalDouble(builder::setVx, toDoubleOrNull(value.get("vx")));
		setOptionalDouble(builder::setVy, toDoubleOrNull(value.get("vy")));
		setOptionalDouble(builder::setVz, toDoubleOrNull(value.get("vz")));
		setOptionalString(builder::setDimension, normalizeText(value.get("dimension")));
		setOptionalString(builder::setEntityType, normalizeText(value.get("entityType")));
		setOptionalString(builder::setEntityName, normalizeText(value.get("entityName")));
		setOptionalDouble(builder::setWidth, toDoubleOrNull(value.get("width")));
		setOptionalDouble(builder::setHeight, toDoubleOrNull(value.get("height")));
		return builder.build();
	}

	private WaypointData buildWaypointData(Map<String, Object> raw) {
		Map<String, Object> value = safeObjectMap(raw);
		WaypointData.Builder builder = WaypointData.newBuilder();
		builder.setX(defaultDouble(toDoubleOrNull(value.get("x"))));
		builder.setY(defaultDouble(toDoubleOrNull(value.get("y"))));
		builder.setZ(defaultDouble(toDoubleOrNull(value.get("z"))));
		builder.setDimension(defaultString(normalizeText(value.get("dimension"))));
		builder.setName(defaultString(normalizeText(value.get("name"))));
		setOptionalString(builder::setSymbol, normalizeText(value.get("symbol")));
		setOptionalInt(builder::setColor, toIntegerOrNull(value.get("color")));
		setOptionalString(builder::setOwnerId, normalizeText(value.get("ownerId")));
		setOptionalString(builder::setOwnerName, normalizeText(value.get("ownerName")));
		setOptionalLong(builder::setCreatedAt, toLongOrNull(value.get("createdAt")));
		setOptionalInt(builder::setTtlSeconds, toIntegerOrNull(value.get("ttlSeconds")));
		setOptionalString(builder::setWaypointKind, normalizeText(value.get("waypointKind")));
		setOptionalBool(builder::setReplaceOldQuick, toBooleanOrNull(value.get("replaceOldQuick")));
		setOptionalInt(builder::setMaxQuickMarks, toIntegerOrNull(value.get("maxQuickMarks")));
		setOptionalString(builder::setTargetType, normalizeText(value.get("targetType")));
		setOptionalString(builder::setTargetEntityId, normalizeText(value.get("targetEntityId")));
		setOptionalString(builder::setTargetEntityType, normalizeText(value.get("targetEntityType")));
		setOptionalString(builder::setTargetEntityName, normalizeText(value.get("targetEntityName")));
		setOptionalString(builder::setRoomCode, normalizeText(value.get("roomCode")));
		setOptionalBool(builder::setPermanent, toBooleanOrNull(value.get("permanent")));
		setOptionalString(builder::setTacticalType, normalizeText(value.get("tacticalType")));
		setOptionalString(builder::setSourceType, normalizeText(value.get("sourceType")));
		setOptionalString(builder::setDeletableBy, normalizeText(value.get("deletableBy")));
		return builder.build();
	}

	private TabPlayerEntry buildTabPlayerEntry(Map<String, Object> raw) {
		Map<String, Object> value = safeObjectMap(raw);
		TabPlayerEntry.Builder builder = TabPlayerEntry.newBuilder();
		setOptionalString(builder::setUuid, normalizeText(value.get("uuid")));
		setOptionalString(builder::setName, normalizeText(value.get("name")));
		setOptionalString(builder::setDisplayName, normalizeText(value.get("displayName")));
		setOptionalString(builder::setPrefixedName, normalizeText(value.get("prefixedName")));
		setOptionalString(builder::setScoreboardTeamId, normalizeText(value.get("scoreboardTeamId")));
		setOptionalString(builder::setScoreboardPrefix, normalizeText(value.get("scoreboardPrefix")));
		setOptionalString(builder::setScoreboardSuffix, normalizeText(value.get("scoreboardSuffix")));
		Integer color = toIntegerOrNull(value.get("scoreboardColorRgb"));
		if (color != null) builder.setScoreboardColorRgb(color & 0xFFFFFF);
		FormattedText formattedDisplay = buildFormattedText(value.get("formattedDisplayName"));
		if (formattedDisplay != null) builder.setFormattedDisplayName(formattedDisplay);
		FormattedText formattedPrefix = buildFormattedText(value.get("formattedScoreboardPrefix"));
		if (formattedPrefix != null) builder.setFormattedScoreboardPrefix(formattedPrefix);
		FormattedText formattedSuffix = buildFormattedText(value.get("formattedScoreboardSuffix"));
		if (formattedSuffix != null) builder.setFormattedScoreboardSuffix(formattedSuffix);
		return builder.build();
	}

	private FormattedText buildFormattedText(Object raw) {
		Map<String, Object> value = safeObjectMap(raw);
		if (value.isEmpty()) return null;
		FormattedText.Builder builder = FormattedText.newBuilder()
				.setPlainText(defaultString(normalizeText(value.get("plainText"))));
		Object spansValue = value.get("spans");
		if (spansValue instanceof List<?> spans) {
			for (Object rawSpan : spans) {
				Map<String, Object> span = safeObjectMap(rawSpan);
				String text = normalizeText(span.get("text"));
				if (text == null) continue;
				FormattedTextSpan.Builder spanBuilder = FormattedTextSpan.newBuilder().setText(text);
				Integer color = toIntegerOrNull(span.get("colorArgb"));
				if (color != null) spanBuilder.setColorArgb(color);
				Integer shadow = toIntegerOrNull(span.get("shadowColorArgb"));
				if (shadow != null) spanBuilder.setShadowColorArgb(shadow);
				setOptionalBool(spanBuilder::setBold, toBooleanOrNull(span.get("bold")));
				setOptionalBool(spanBuilder::setItalic, toBooleanOrNull(span.get("italic")));
				setOptionalBool(spanBuilder::setUnderlined, toBooleanOrNull(span.get("underlined")));
				setOptionalBool(spanBuilder::setStrikethrough, toBooleanOrNull(span.get("strikethrough")));
				setOptionalBool(spanBuilder::setObfuscated, toBooleanOrNull(span.get("obfuscated")));
				setOptionalString(spanBuilder::setFontId, normalizeText(span.get("fontId")));
				builder.addSpans(spanBuilder.build());
			}
		}
		return builder.build();
	}

	private ProtocolPackets.HandshakeAckInboundPacket decodeHandshakeAck(HandshakeAck message) {
		ProtocolPackets.HandshakeAckInboundPacket packet = new ProtocolPackets.HandshakeAckInboundPacket();
		packet.type = "handshake_ack";
		packet.ready = message.getReady();
		packet.networkProtocolVersion = message.getNetworkProtocolVersion();
		packet.minimumCompatibleNetworkProtocolVersion = message.getMinimumCompatibleNetworkProtocolVersion();
		packet.localProgramVersion = message.getLocalProgramVersion();
		packet.programVersion = packet.localProgramVersion;
		packet.error = message.hasError() ? message.getError() : null;
		packet.rejectReason = message.hasRejectReason() ? message.getRejectReason() : null;
		packet.deltaEnabled = message.getDeltaEnabled();
		packet.digestIntervalSec = message.hasDigestIntervalSec() ? message.getDigestIntervalSec() : null;
		packet.broadcastHz = message.hasBroadcastHz() ? message.getBroadcastHz() : null;
		packet.reportIntervalTicks = message.hasReportIntervalTicks() ? message.getReportIntervalTicks() : null;
		packet.playerTimeoutSec = message.hasPlayerTimeoutSec() ? message.getPlayerTimeoutSec() : null;
		packet.entityTimeoutSec = message.hasEntityTimeoutSec() ? message.getEntityTimeoutSec() : null;
		packet.battleChunkTimeoutSec = message.hasBattleChunkTimeoutSec() ? message.getBattleChunkTimeoutSec() : null;
		packet.tabHistory = message.hasTabHistory() ? tabHistoryCapabilitiesToMap(message.getTabHistory()) : null;
		packet.relationshipQuery = message.hasRelationshipQuery()
				? relationshipQueryCapabilitiesToMap(message.getRelationshipQuery()) : null;
		packet.reportPolicy = message.hasReportPolicy() ? reportPolicyToMap(message.getReportPolicy()) : null;
		packet.uplinkMovementDatagramAccepted = message.getUnreliableChannelsAcceptedList().stream()
				.anyMatch(value -> value == UnreliableChannel.UNRELIABLE_CHANNEL_MOVEMENT);
		packet.downlinkMovementDatagramAccepted = message.getDownlinkChannelsAcceptedList().stream()
				.anyMatch(value -> value == UnreliableChannel.UNRELIABLE_CHANNEL_MOVEMENT);
		return packet;
	}

	private ProtocolPackets.TabHistoryDigestInboundPacket decodeTabHistoryDigest(TabHistoryDigest message) {
		ProtocolPackets.TabHistoryDigestInboundPacket packet = new ProtocolPackets.TabHistoryDigestInboundPacket();
		packet.type = "tab_history_digest";
		packet.head = message.hasHead() ? tabHistoryHeadToMap(message.getHead()) : Map.of();
		return packet;
	}

	private ProtocolPackets.TabHistorySyncChunkInboundPacket decodeTabHistorySyncChunk(TabHistorySyncChunk message) {
		ProtocolPackets.TabHistorySyncChunkInboundPacket packet = new ProtocolPackets.TabHistorySyncChunkInboundPacket();
		packet.type = "tab_history_sync_chunk";
		packet.requestId = message.getRequestId();
		packet.mode = message.getMode().name();
		packet.head = message.hasHead() ? tabHistoryHeadToMap(message.getHead()) : Map.of();
		packet.upsert = message.getUpsertList().stream().map(this::tabHistoryEntryToMap).toList();
		packet.deleteUuids = new ArrayList<>(message.getDeleteUuidsList());
		packet.chunkIndex = message.getChunkIndex();
		packet.chunkCount = message.getChunkCount();
		packet.finalChunk = message.getFinal();
		packet.resetReason = message.hasResetReason() ? message.getResetReason().name() : null;
		packet.errorCode = message.hasErrorCode() ? message.getErrorCode().name() : null;
		return packet;
	}

	private ProtocolPackets.TabHistoryLookupChunkInboundPacket decodeTabHistoryLookupChunk(TabHistoryLookupChunk message) {
		ProtocolPackets.TabHistoryLookupChunkInboundPacket packet = new ProtocolPackets.TabHistoryLookupChunkInboundPacket();
		packet.type = "tab_history_lookup_chunk";
		packet.requestId = message.getRequestId();
		packet.head = message.hasHead() ? tabHistoryHeadToMap(message.getHead()) : Map.of();
		packet.results = new ArrayList<>();
		for (TabHistoryLookupResult result : message.getResultsList()) {
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("selectorIndex", result.getSelectorIndex());
			value.put("entries", result.getEntriesList().stream().map(this::tabHistoryEntryToMap).toList());
			packet.results.add(value);
		}
		packet.chunkIndex = message.getChunkIndex();
		packet.chunkCount = message.getChunkCount();
		packet.finalChunk = message.getFinal();
		packet.errorCode = message.hasErrorCode() ? message.getErrorCode().name() : null;
		return packet;
	}

	private Map<String, Object> tabHistoryCapabilitiesToMap(TabHistoryCapabilities value) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("supported", value.getSupported());
		result.put("syncModes", value.getSyncModesList().stream().map(Enum::name).toList());
		result.put("defaultChunkEntries", value.getDefaultChunkEntries());
		result.put("maxChunkEntries", value.getMaxChunkEntries());
		result.put("maxChunkBytes", value.getMaxChunkBytes());
		result.put("maxLookupSelectors", value.getMaxLookupSelectors());
		result.put("retentionDays", value.getRetentionDays());
		result.put("deltaRetentionDays", value.getDeltaRetentionDays());
		return result;
	}

	private Map<String, Object> relationshipQueryCapabilitiesToMap(RelationshipQueryCapabilities value) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("supported", value.getSupported());
		result.put("maxSelectors", value.getMaxSelectors());
		result.put("defaultChunkEntries", value.getDefaultChunkEntries());
		result.put("maxChunkEntries", value.getMaxChunkEntries());
		result.put("maxChunkBytes", value.getMaxChunkBytes());
		result.put("relationKinds", value.getRelationKindsList().stream().map(Enum::name).toList());
		return result;
	}

	private Map<String, Object> reportPolicyToMap(PlayerReportPolicy value) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("recommendations", value.getRecommendationsList().stream().map(recommendation -> {
			Map<String, Object> mapped = new LinkedHashMap<>();
			mapped.put("scope", recommendation.getScope().name());
			mapped.put("mode", recommendation.getMode().name());
			mapped.put("reason", recommendation.getReason());
			if (recommendation.hasReplacementDatasetId()) {
				mapped.put("replacementDatasetId", recommendation.getReplacementDatasetId());
			}
			return mapped;
		}).toList());
		return result;
	}

	private Map<String, Object> tabHistoryHeadToMap(TabHistoryHead value) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("revision", value.getRevision());
		result.put("digestSha256", value.getDigestSha256().toByteArray());
		result.put("recordCount", value.getRecordCount());
		result.put("generatedAtUtcMs", value.getGeneratedAtUtcMs());
		result.put("oldestAvailableDeltaRevision", value.getOldestAvailableDeltaRevision());
		return result;
	}

	private Map<String, Object> tabHistoryEntryToMap(TabHistoryEntry value) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("player", tabPlayerEntryToMap(value.getPlayer()));
		result.put("labelFirstObservedAtUtcMs", value.getLabelFirstObservedAtUtcMs());
		result.put("lastObservedAtUtcMs", value.getLastObservedAtUtcMs());
		result.put("revision", value.getRevision());
		result.put("etagSha256", value.getEtagSha256().toByteArray());
		return result;
	}

	private Map<String, Object> tabPlayerEntryToMap(TabPlayerEntry value) {
		Map<String, Object> result = new LinkedHashMap<>();
		if (value.hasUuid()) result.put("uuid", value.getUuid());
		if (value.hasName()) result.put("name", value.getName());
		if (value.hasDisplayName()) result.put("displayName", value.getDisplayName());
		if (value.hasPrefixedName()) result.put("prefixedName", value.getPrefixedName());
		if (value.hasScoreboardTeamId()) result.put("scoreboardTeamId", value.getScoreboardTeamId());
		if (value.hasScoreboardPrefix()) result.put("scoreboardPrefix", value.getScoreboardPrefix());
		if (value.hasScoreboardSuffix()) result.put("scoreboardSuffix", value.getScoreboardSuffix());
		if (value.hasScoreboardColorRgb()) result.put("scoreboardColorRgb", value.getScoreboardColorRgb());
		return result;
	}

	private TabHistorySyncMode tabHistorySyncMode(String value) {
		return "TAB_HISTORY_SYNC_MODE_DELTA".equals(value)
				? TabHistorySyncMode.TAB_HISTORY_SYNC_MODE_DELTA
				: TabHistorySyncMode.TAB_HISTORY_SYNC_MODE_FULL;
	}

	private ProtocolPackets.SnapshotFullInboundPacket decodeSnapshotFull(SnapshotFull message) {
		ProtocolPackets.SnapshotFullInboundPacket packet = new ProtocolPackets.SnapshotFullInboundPacket();
		packet.type = "snapshot_full";
		packet.players = decodePlayerDataMap(message.getPlayersMap());
		packet.entities = decodeEntityDataMap(message.getEntitiesMap());
		packet.waypoints = decodeWaypointDataMap(message.getWaypointsMap());
		packet.battleChunks = decodeBattleChunkSnapshot(message.getBattleChunksList());
		packet.playerMarks = decodePlayerMarkFullMap(message.getPlayerMarksMap());
		packet.lastSeenPlayers = decodeLastSeenPlayerDataMap(message.getLastSeenPlayersMap());
		return packet;
	}

	private ProtocolPackets.PatchInboundPacket decodePatch(Patch message) {
		ProtocolPackets.PatchInboundPacket packet = new ProtocolPackets.PatchInboundPacket();
		packet.type = "patch";
		packet.players = message.hasPlayers() ? decodePlayerPatchScope(message.getPlayers()) : null;
		packet.entities = message.hasEntities() ? decodeEntityPatchScope(message.getEntities()) : null;
		packet.waypoints = message.hasWaypoints() ? decodeWaypointPatchScope(message.getWaypoints()) : null;
		packet.battleChunks = message.hasBattleChunks() ? decodeBattleChunkPatchScope(message.getBattleChunks()) : null;
		packet.playerMarks = message.hasPlayerMarks() ? decodePlayerMarkPatchScope(message.getPlayerMarks()) : null;
		packet.lastSeenPlayers = message.hasLastSeenPlayers()
				? decodeLastSeenPlayerPatchScope(message.getLastSeenPlayers()) : null;
		packet.meta = null;
		return packet;
	}

	private ProtocolPackets.DigestInboundPacket decodeDigest(Digest message) {
		ProtocolPackets.DigestInboundPacket packet = new ProtocolPackets.DigestInboundPacket();
		packet.type = "digest";
		packet.hashes = new LinkedHashMap<>();
		packet.hashes.put("players", message.getPlayers());
		packet.hashes.put("entities", message.getEntities());
		packet.hashes.put("waypoints", message.getWaypoints());
		if (message.hasBattleChunks()) {
			packet.hashes.put("battleChunks", message.getBattleChunks());
		}
		if (message.hasLastSeenPlayers()) {
			packet.hashes.put("lastSeenPlayers", message.getLastSeenPlayers());
		}
		return packet;
	}

	private ProtocolPackets.RefreshReqInboundPacket decodeRefreshRequest(RefreshRequest message) {
		ProtocolPackets.RefreshReqInboundPacket packet = new ProtocolPackets.RefreshReqInboundPacket();
		packet.type = "refresh_req";
		packet.players = new ArrayList<>(message.getPlayersList());
		packet.entities = new ArrayList<>(message.getEntitiesList());
		packet.battleChunks = decodeBattleChunkRefs(message.getBattleChunksList());
		packet.reason = message.getReason();
		return packet;
	}

	private ProtocolPackets.ReportRateHintInboundPacket decodeReportRateHint(ReportRateHint message) {
		ProtocolPackets.ReportRateHintInboundPacket packet = new ProtocolPackets.ReportRateHintInboundPacket();
		packet.type = "report_rate_hint";
		packet.reportIntervalTicks = message.getReportIntervalTicks();
		packet.broadcastHz = message.getBroadcastHz();
		packet.reason = message.hasReason() ? message.getReason() : null;
		return packet;
	}

	private ProtocolPackets.PlayerDirectoryLookupChunkInboundPacket decodePlayerDirectoryLookupChunk(
			PlayerDirectoryLookupChunk message) {
		ProtocolPackets.PlayerDirectoryLookupChunkInboundPacket packet =
				new ProtocolPackets.PlayerDirectoryLookupChunkInboundPacket();
		packet.type = "player_directory_lookup_chunk";
		packet.requestId = message.getRequestId();
		packet.results = message.getResultsList().stream().map(this::playerDirectoryResultToMap).toList();
		packet.chunkIndex = message.getChunkIndex();
		packet.chunkCount = message.getChunkCount();
		packet.finalChunk = message.getFinal();
		packet.errorCode = message.hasErrorCode() ? message.getErrorCode().name() : null;
		return packet;
	}

	private ProtocolPackets.PlayerRelationQueryChunkInboundPacket decodePlayerRelationQueryChunk(
			PlayerRelationQueryChunk message) {
		ProtocolPackets.PlayerRelationQueryChunkInboundPacket packet =
				new ProtocolPackets.PlayerRelationQueryChunkInboundPacket();
		packet.type = "player_relation_query_chunk";
		packet.requestId = message.getRequestId();
		packet.subjects = message.getSubjectsList().stream().map(this::playerDirectoryEntryToMap).toList();
		packet.results = message.getResultsList().stream().map(this::playerRelationResultToMap).toList();
		packet.chunkIndex = message.getChunkIndex();
		packet.chunkCount = message.getChunkCount();
		packet.finalChunk = message.getFinal();
		packet.errorCode = message.hasErrorCode() ? message.getErrorCode().name() : null;
		return packet;
	}

	private Map<String, Object> decodePlayerDataMap(Map<String, PlayerData> raw) {
		Map<String, Object> mapped = new LinkedHashMap<>();
		for (Map.Entry<String, PlayerData> entry : raw.entrySet()) {
			mapped.put(entry.getKey(), playerDataToMap(entry.getValue()));
		}
		return mapped;
	}

	private Map<String, Object> decodeEntityDataMap(Map<String, EntityData> raw) {
		Map<String, Object> mapped = new LinkedHashMap<>();
		for (Map.Entry<String, EntityData> entry : raw.entrySet()) {
			mapped.put(entry.getKey(), entityDataToMap(entry.getValue()));
		}
		return mapped;
	}

	private Map<String, Object> decodeWaypointDataMap(Map<String, WaypointData> raw) {
		Map<String, Object> mapped = new LinkedHashMap<>();
		for (Map.Entry<String, WaypointData> entry : raw.entrySet()) {
			mapped.put(entry.getKey(), waypointDataToMap(entry.getValue()));
		}
		return mapped;
	}

	private Map<String, Object> decodePlayerMarkFullMap(Map<String, PlayerMark> raw) {
		Map<String, Object> mapped = new LinkedHashMap<>();
		for (Map.Entry<String, PlayerMark> entry : raw.entrySet()) {
			mapped.put(entry.getKey(), playerMarkToMap(entry.getValue()));
		}
		return mapped;
	}

	private Map<String, Object> decodeLastSeenPlayerDataMap(Map<String, LastSeenPlayerData> raw) {
		Map<String, Object> mapped = new LinkedHashMap<>();
		for (Map.Entry<String, LastSeenPlayerData> entry : raw.entrySet()) {
			mapped.put(entry.getKey(), lastSeenPlayerDataToMap(entry.getValue()));
		}
		return mapped;
	}

	private Map<String, Object> decodePlayerPatchScope(PlayerPatchScope scope) {
		Map<String, Object> patch = new LinkedHashMap<>();
		Map<String, Object> upsert = new LinkedHashMap<>();
		for (PlayerUpsert item : scope.getUpsertList()) {
			if (item.getId().isBlank()) {
				continue;
			}
			Map<String, Object> data = playerDeltaToMap(item.getData());
			applyClearFields(data, item.getClearFieldsList());
			upsert.put(item.getId(), data);
		}
		patch.put("upsert", upsert);
		patch.put("delete", new ArrayList<>(scope.getDeleteList()));
		return patch;
	}

	private Map<String, Object> decodeEntityPatchScope(EntityPatchScope scope) {
		Map<String, Object> patch = new LinkedHashMap<>();
		Map<String, Object> upsert = new LinkedHashMap<>();
		for (EntityUpsert item : scope.getUpsertList()) {
			if (item.getId().isBlank()) {
				continue;
			}
			Map<String, Object> data = entityDeltaToMap(item.getData());
			applyClearFields(data, item.getClearFieldsList());
			upsert.put(item.getId(), data);
		}
		patch.put("upsert", upsert);
		patch.put("delete", new ArrayList<>(scope.getDeleteList()));
		return patch;
	}

	private Map<String, Object> decodeWaypointPatchScope(WaypointPatchScope scope) {
		Map<String, Object> patch = new LinkedHashMap<>();
		Map<String, Object> upsert = new LinkedHashMap<>();
		for (WaypointUpsert item : scope.getUpsertList()) {
			if (item.getId().isBlank()) {
				continue;
			}
			Map<String, Object> data = waypointDeltaToMap(item.getData());
			applyClearFields(data, item.getClearFieldsList());
			upsert.put(item.getId(), data);
		}
		patch.put("upsert", upsert);
		patch.put("delete", new ArrayList<>(scope.getDeleteList()));
		return patch;
	}

	private ProtocolPackets.BattleChunkPatchData decodeBattleChunkPatchScope(BattleChunkPatchScope scope) {
		List<ProtocolPackets.BattleChunkUpsertData> upsert = new ArrayList<>();
		for (BattleChunkUpsert item : scope.getUpsertList()) {
			BattleChunkRefData ref = battleChunkRefData(item.getRef());
			if (ref == null) {
				continue;
			}
			upsert.add(new ProtocolPackets.BattleChunkUpsertData(
					ref,
					battleChunkValueToMap(item.getData()),
					new ArrayList<>(item.getClearFieldsList())
			));
		}
		return new ProtocolPackets.BattleChunkPatchData(
				upsert,
				decodeBattleChunkRefs(scope.getDeleteList())
		);
	}

	private Map<String, Object> decodePlayerMarkPatchScope(PlayerMarkPatchScope scope) {
		Map<String, Object> patch = new LinkedHashMap<>();
		Map<String, Object> upsert = new LinkedHashMap<>();
		for (PlayerMarkUpsert item : scope.getUpsertList()) {
			if (item.getId().isBlank()) {
				continue;
			}
			Map<String, Object> data = playerMarkToMap(item.getData());
			applyClearFields(data, item.getClearFieldsList());
			upsert.put(item.getId(), data);
		}
		patch.put("upsert", upsert);
		patch.put("delete", new ArrayList<>(scope.getDeleteList()));
		return patch;
	}

	private Map<String, Object> decodeLastSeenPlayerPatchScope(LastSeenPlayerPatchScope scope) {
		Map<String, Object> patch = new LinkedHashMap<>();
		Map<String, Object> upsert = new LinkedHashMap<>();
		for (LastSeenPlayerUpsert item : scope.getUpsertList()) {
			if (!item.getId().isBlank()) {
				Map<String, Object> data = lastSeenPlayerDataToMap(item.getData());
				applyClearFields(data, item.getClearFieldsList());
				upsert.put(item.getId(), data);
			}
		}
		patch.put("upsert", upsert);
		patch.put("delete", new ArrayList<>(scope.getDeleteList()));
		return patch;
	}

	private void applyClearFields(Map<String, Object> data, List<String> clearFields) {
		if (data == null || clearFields == null) {
			return;
		}
		for (String fieldName : clearFields) {
			if (fieldName != null && !fieldName.isBlank()) {
				data.put(fieldName, null);
			}
		}
	}

	private Map<String, Object> lastSeenPlayerDataToMap(LastSeenPlayerData value) {
		Map<String, Object> mapped = new LinkedHashMap<>();
		mapped.put("x", value.getX());
		mapped.put("y", value.getY());
		mapped.put("z", value.getZ());
		mapped.put("dimension", value.getDimension());
		mapped.put("playerName", value.getPlayerName());
		mapped.put("playerUUID", value.getPlayerUuid());
		mapped.put("lastSeenAtUtcMs", value.getLastSeenAtUtcMs());
		mapped.put("positionObservedAtUtcMs", value.getPositionObservedAtUtcMs());
		mapped.put("offlineDetectedAtUtcMs", value.getOfflineDetectedAtUtcMs());
		return mapped;
	}

	private Map<String, Object> playerDirectoryResultToMap(PlayerDirectoryLookupResult value) {
		Map<String, Object> mapped = new LinkedHashMap<>();
		mapped.put("selectorIndex", value.getSelectorIndex());
		mapped.put("entries", value.getEntriesList().stream().map(this::playerDirectoryEntryToMap).toList());
		if (value.hasErrorCode()) mapped.put("errorCode", value.getErrorCode().name());
		return mapped;
	}

	private Map<String, Object> playerDirectoryEntryToMap(PlayerDirectoryEntry value) {
		Map<String, Object> mapped = new LinkedHashMap<>();
		if (value.hasPlayer()) mapped.put("player", playerIdentityToMap(value.getPlayer()));
		mapped.put("affiliations", value.getAffiliationsList().stream().map(affiliation -> {
			Map<String, Object> group = new LinkedHashMap<>();
			group.put("affiliationId", affiliation.getAffiliationId());
			group.put("kind", affiliation.getKind().name());
			group.put("name", affiliation.getName());
			if (affiliation.hasParentAffiliationId()) {
				group.put("parentAffiliationId", affiliation.getParentAffiliationId());
			}
			if (affiliation.hasColorRgb()) group.put("colorRgb", affiliation.getColorRgb());
			return group;
		}).toList());
		mapped.put("datasetId", value.getDatasetId());
		mapped.put("observedAtUtcMs", value.getObservedAtUtcMs());
		mapped.put("stale", value.getStale());
		return mapped;
	}

	private Map<String, Object> playerIdentityToMap(PlayerIdentityRecord value) {
		Map<String, Object> mapped = new LinkedHashMap<>();
		mapped.put("playerId", value.getPlayerId());
		if (value.hasUuid()) mapped.put("uuid", value.getUuid());
		mapped.put("name", value.getName());
		mapped.put("aliases", new ArrayList<>(value.getAliasesList()));
		mapped.put("affiliationIds", new ArrayList<>(value.getAffiliationIdsList()));
		return mapped;
	}

	private Map<String, Object> playerRelationResultToMap(PlayerRelationResult value) {
		Map<String, Object> mapped = new LinkedHashMap<>();
		if (value.hasTarget()) mapped.put("target", playerDirectoryEntryToMap(value.getTarget()));
		mapped.put("relation", value.getRelation().name());
		mapped.put("evidence", value.getEvidenceList().stream().map(evidence -> {
			Map<String, Object> mappedEvidence = new LinkedHashMap<>();
			mappedEvidence.put("relation", evidence.getRelation().name());
			mappedEvidence.put("sourceAffiliationId", evidence.getSourceAffiliationId());
			mappedEvidence.put("targetAffiliationId", evidence.getTargetAffiliationId());
			if (evidence.hasAffiliationRelation()) {
				mappedEvidence.put("affiliationRelation", evidence.getAffiliationRelation().name());
			}
			mappedEvidence.put("reason", evidence.getReason());
			return mappedEvidence;
		}).toList());
		return mapped;
	}

	private List<ProtocolPackets.BattleChunkEntryData> decodeBattleChunkSnapshot(List<BattleChunkEntry> entries) {
		List<ProtocolPackets.BattleChunkEntryData> mapped = new ArrayList<>();
		for (BattleChunkEntry entry : entries) {
			BattleChunkRefData ref = battleChunkRefData(entry.getRef());
			if (ref == null) {
				continue;
			}
			mapped.add(new ProtocolPackets.BattleChunkEntryData(
					ref,
					battleChunkValueToMap(entry.getData())
			));
		}
		return mapped;
	}

	private List<BattleChunkRefData> decodeBattleChunkRefs(List<BattleChunkRef> refs) {
		List<BattleChunkRefData> decoded = new ArrayList<>();
		for (BattleChunkRef ref : refs) {
			BattleChunkRefData value = battleChunkRefData(ref);
			if (value != null) {
				decoded.add(value);
			}
		}
		return decoded;
	}

	private Map<String, Object> playerDataToMap(PlayerData value) {
		Map<String, Object> mapped = new LinkedHashMap<>();
		mapped.put("x", value.getX());
		mapped.put("y", value.getY());
		mapped.put("z", value.getZ());
		if (value.hasVx()) mapped.put("vx", value.getVx());
		if (value.hasVy()) mapped.put("vy", value.getVy());
		if (value.hasVz()) mapped.put("vz", value.getVz());
		mapped.put("dimension", value.getDimension());
		if (value.hasPlayerName()) mapped.put("playerName", value.getPlayerName());
		if (value.hasPlayerUuid()) mapped.put("playerUUID", value.getPlayerUuid());
		if (value.hasHealth()) mapped.put("health", value.getHealth());
		if (value.hasMaxHealth()) mapped.put("maxHealth", value.getMaxHealth());
		if (value.hasArmor()) mapped.put("armor", value.getArmor());
		if (value.hasIsRiding()) mapped.put("isRiding", value.getIsRiding());
		if (value.hasWidth()) mapped.put("width", value.getWidth());
		if (value.hasHeight()) mapped.put("height", value.getHeight());
		if (value.hasPositionSourceId()) mapped.put("positionSourceId", value.getPositionSourceId());
		if (value.hasPositionSourceKind()) mapped.put("positionSourceKind", value.getPositionSourceKind().name());
		if (value.hasPositionSourceDisplayName()) mapped.put("positionSourceDisplayName", value.getPositionSourceDisplayName());
		if (value.hasPositionResolution()) mapped.put("positionResolution", value.getPositionResolution());
		return mapped;
	}

	private Map<String, Object> playerDeltaToMap(PlayerDelta value) {
		Map<String, Object> mapped = new LinkedHashMap<>();
		if (value.hasX()) mapped.put("x", value.getX());
		if (value.hasY()) mapped.put("y", value.getY());
		if (value.hasZ()) mapped.put("z", value.getZ());
		if (value.hasVx()) mapped.put("vx", value.getVx());
		if (value.hasVy()) mapped.put("vy", value.getVy());
		if (value.hasVz()) mapped.put("vz", value.getVz());
		if (value.hasDimension()) mapped.put("dimension", value.getDimension());
		if (value.hasPlayerName()) mapped.put("playerName", value.getPlayerName());
		if (value.hasPlayerUuid()) mapped.put("playerUUID", value.getPlayerUuid());
		if (value.hasHealth()) mapped.put("health", value.getHealth());
		if (value.hasMaxHealth()) mapped.put("maxHealth", value.getMaxHealth());
		if (value.hasArmor()) mapped.put("armor", value.getArmor());
		if (value.hasIsRiding()) mapped.put("isRiding", value.getIsRiding());
		if (value.hasWidth()) mapped.put("width", value.getWidth());
		if (value.hasHeight()) mapped.put("height", value.getHeight());
		if (value.hasPositionSourceId()) mapped.put("positionSourceId", value.getPositionSourceId());
		if (value.hasPositionSourceKind()) mapped.put("positionSourceKind", value.getPositionSourceKind().name());
		if (value.hasPositionSourceDisplayName()) mapped.put("positionSourceDisplayName", value.getPositionSourceDisplayName());
		if (value.hasPositionResolution()) mapped.put("positionResolution", value.getPositionResolution());
		return mapped;
	}

	private Map<String, Object> entityDataToMap(EntityData value) {
		Map<String, Object> mapped = new LinkedHashMap<>();
		mapped.put("x", value.getX());
		mapped.put("y", value.getY());
		mapped.put("z", value.getZ());
		if (value.hasVx()) mapped.put("vx", value.getVx());
		if (value.hasVy()) mapped.put("vy", value.getVy());
		if (value.hasVz()) mapped.put("vz", value.getVz());
		mapped.put("dimension", value.getDimension());
		if (value.hasEntityType()) mapped.put("entityType", value.getEntityType());
		if (value.hasEntityName()) mapped.put("entityName", value.getEntityName());
		if (value.hasWidth()) mapped.put("width", value.getWidth());
		if (value.hasHeight()) mapped.put("height", value.getHeight());
		return mapped;
	}

	private Map<String, Object> entityDeltaToMap(EntityDelta value) {
		Map<String, Object> mapped = new LinkedHashMap<>();
		if (value.hasX()) mapped.put("x", value.getX());
		if (value.hasY()) mapped.put("y", value.getY());
		if (value.hasZ()) mapped.put("z", value.getZ());
		if (value.hasVx()) mapped.put("vx", value.getVx());
		if (value.hasVy()) mapped.put("vy", value.getVy());
		if (value.hasVz()) mapped.put("vz", value.getVz());
		if (value.hasDimension()) mapped.put("dimension", value.getDimension());
		if (value.hasEntityType()) mapped.put("entityType", value.getEntityType());
		if (value.hasEntityName()) mapped.put("entityName", value.getEntityName());
		if (value.hasWidth()) mapped.put("width", value.getWidth());
		if (value.hasHeight()) mapped.put("height", value.getHeight());
		return mapped;
	}

	private Map<String, Object> waypointDataToMap(WaypointData value) {
		Map<String, Object> mapped = new LinkedHashMap<>();
		mapped.put("x", value.getX());
		mapped.put("y", value.getY());
		mapped.put("z", value.getZ());
		mapped.put("dimension", value.getDimension());
		mapped.put("name", value.getName());
		if (value.hasSymbol()) mapped.put("symbol", value.getSymbol());
		if (value.hasColor()) mapped.put("color", value.getColor());
		if (value.hasOwnerId()) mapped.put("ownerId", value.getOwnerId());
		if (value.hasOwnerName()) mapped.put("ownerName", value.getOwnerName());
		if (value.hasCreatedAt()) mapped.put("createdAt", value.getCreatedAt());
		if (value.hasTtlSeconds()) mapped.put("ttlSeconds", value.getTtlSeconds());
		if (value.hasWaypointKind()) mapped.put("waypointKind", value.getWaypointKind());
		if (value.hasReplaceOldQuick()) mapped.put("replaceOldQuick", value.getReplaceOldQuick());
		if (value.hasMaxQuickMarks()) mapped.put("maxQuickMarks", value.getMaxQuickMarks());
		if (value.hasTargetType()) mapped.put("targetType", value.getTargetType());
		if (value.hasTargetEntityId()) mapped.put("targetEntityId", value.getTargetEntityId());
		if (value.hasTargetEntityType()) mapped.put("targetEntityType", value.getTargetEntityType());
		if (value.hasTargetEntityName()) mapped.put("targetEntityName", value.getTargetEntityName());
		if (value.hasRoomCode()) mapped.put("roomCode", value.getRoomCode());
		if (value.hasPermanent()) mapped.put("permanent", value.getPermanent());
		if (value.hasTacticalType()) mapped.put("tacticalType", value.getTacticalType());
		if (value.hasSourceType()) mapped.put("sourceType", value.getSourceType());
		if (value.hasDeletableBy()) mapped.put("deletableBy", value.getDeletableBy());
		return mapped;
	}

	private Map<String, Object> waypointDeltaToMap(WaypointDelta value) {
		Map<String, Object> mapped = new LinkedHashMap<>();
		if (value.hasX()) mapped.put("x", value.getX());
		if (value.hasY()) mapped.put("y", value.getY());
		if (value.hasZ()) mapped.put("z", value.getZ());
		if (value.hasDimension()) mapped.put("dimension", value.getDimension());
		if (value.hasName()) mapped.put("name", value.getName());
		if (value.hasSymbol()) mapped.put("symbol", value.getSymbol());
		if (value.hasColor()) mapped.put("color", value.getColor());
		if (value.hasOwnerId()) mapped.put("ownerId", value.getOwnerId());
		if (value.hasOwnerName()) mapped.put("ownerName", value.getOwnerName());
		if (value.hasCreatedAt()) mapped.put("createdAt", value.getCreatedAt());
		if (value.hasTtlSeconds()) mapped.put("ttlSeconds", value.getTtlSeconds());
		if (value.hasWaypointKind()) mapped.put("waypointKind", value.getWaypointKind());
		if (value.hasReplaceOldQuick()) mapped.put("replaceOldQuick", value.getReplaceOldQuick());
		if (value.hasMaxQuickMarks()) mapped.put("maxQuickMarks", value.getMaxQuickMarks());
		if (value.hasTargetType()) mapped.put("targetType", value.getTargetType());
		if (value.hasTargetEntityId()) mapped.put("targetEntityId", value.getTargetEntityId());
		if (value.hasTargetEntityType()) mapped.put("targetEntityType", value.getTargetEntityType());
		if (value.hasTargetEntityName()) mapped.put("targetEntityName", value.getTargetEntityName());
		if (value.hasRoomCode()) mapped.put("roomCode", value.getRoomCode());
		if (value.hasPermanent()) mapped.put("permanent", value.getPermanent());
		if (value.hasTacticalType()) mapped.put("tacticalType", value.getTacticalType());
		if (value.hasSourceType()) mapped.put("sourceType", value.getSourceType());
		if (value.hasDeletableBy()) mapped.put("deletableBy", value.getDeletableBy());
		return mapped;
	}

	private Map<String, Object> playerMarkToMap(PlayerMark value) {
		Map<String, Object> mapped = new LinkedHashMap<>();
		mapped.put("team", value.getTeam());
		if (value.hasColor()) mapped.put("color", value.getColor());
		if (value.hasLabel()) mapped.put("label", value.getLabel());
		if (value.hasSource()) mapped.put("source", value.getSource());
		return mapped;
	}

	private Map<String, Object> battleChunkValueToMap(BattleChunkValue value) {
		Map<String, Object> mapped = new LinkedHashMap<>();
		if (value.hasSymbol()) mapped.put("symbol", value.getSymbol());
		if (value.hasMarkerType()) mapped.put("markerType", value.getMarkerType());
		mapped.put("colorRaw", value.getColorRaw());
		if (value.hasColorNote()) mapped.put("colorNote", value.getColorNote());
		if (value.hasRoomCode()) mapped.put("roomCode", value.getRoomCode());
		if (value.hasColorMode()) mapped.put("colorMode", value.getColorMode());
		if (value.hasColorSemanticKey()) mapped.put("colorSemanticKey", value.getColorSemanticKey());
		if (value.hasMode()) mapped.put("mode", value.getMode());
		if (value.hasObservedAt()) mapped.put("observedAt", value.getObservedAt());
		if (value.hasPositionSampledAt()) mapped.put("positionSampledAt", value.getPositionSampledAt());
		if (value.hasAlignmentSource()) mapped.put("alignmentSource", value.getAlignmentSource());
		if (value.hasReporterId()) mapped.put("reporterId", value.getReporterId());
		return mapped;
	}

	private List<BattleChunkRef> buildBattleChunkRefs(List<BattleChunkRefData> references) {
		List<BattleChunkRef> encoded = new ArrayList<>();
		if (references == null) {
			return encoded;
		}
		for (BattleChunkRefData ref : references) {
			if (ref == null) {
				continue;
			}
			encoded.add(
					BattleChunkRef.newBuilder()
							.setDimension(ref.dimension())
							.setCoord(
									BattleChunkCoord.newBuilder()
											.setChunkX(ref.chunkX())
											.setChunkZ(ref.chunkZ())
											.build()
							)
							.build()
			);
		}
		return encoded;
	}

	private BattleChunkRefData battleChunkRefData(BattleChunkRef ref) {
		if (ref == null || !ref.hasCoord()) {
			return null;
		}
		String dimension = normalizeText(ref.getDimension());
		if (dimension == null) return null;
		return new BattleChunkRefData(
				dimension,
				ref.getCoord().getChunkX(),
				ref.getCoord().getChunkZ()
		);
	}

	private static Map<String, Map<String, Object>> safeMap(Map<String, Map<String, Object>> value) {
		return value == null ? Map.of() : value;
	}

	private static Map<String, Object> safeObjectMap(Map<String, Object> value) {
		return value == null ? Map.of() : value;
	}

	private static Map<String, Object> safeObjectMap(Object value) {
		if (!(value instanceof Map<?, ?> raw)) return Map.of();
		Map<String, Object> result = new LinkedHashMap<>();
		raw.forEach((key, item) -> result.put(String.valueOf(key), item));
		return result;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> safeListOfMaps(List<Map<String, Object>> value) {
		return value == null ? List.of() : value;
	}

	private static List<String> cleanStringList(List<String> value) {
		if (value == null || value.isEmpty()) {
			return List.of();
		}
		List<String> cleaned = new ArrayList<>();
		for (String item : value) {
			String normalized = normalizeText(item);
			if (normalized != null) {
				cleaned.add(normalized);
			}
		}
		return cleaned;
	}

	private static String uuidString(byte[] raw) {
		return UuidBinaryCodec.toCanonicalString(raw);
	}

	private static String defaultString(String value) {
		return value == null ? "" : value;
	}

	private static int defaultInt(Integer value) {
		return value == null ? 0 : value;
	}

	private static long defaultLong(Long value) {
		return value == null ? 0L : value;
	}

	private static double defaultDouble(Double value) {
		return value == null ? 0.0d : value;
	}

	private static String normalizeText(Object value) {
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return text.isEmpty() ? null : text;
	}

	private static Double toDoubleOrNull(Object value) {
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		if (value instanceof String text) {
			try {
				return Double.parseDouble(text.trim());
			} catch (Exception ignored) {
				return null;
			}
		}
		return null;
	}

	private static Integer toIntegerOrNull(Object value) {
		if (value instanceof Integer integer) {
			return integer;
		}
		if (value instanceof Number number) {
			double candidate = number.doubleValue();
			if (!Double.isFinite(candidate) || candidate % 1.0d != 0.0d) {
				return null;
			}
			long longValue = (long) candidate;
			return longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE ? (int) longValue : null;
		}
		if (value instanceof String text) {
			try {
				return Integer.parseInt(text.trim());
			} catch (Exception ignored) {
				return null;
			}
		}
		return null;
	}

	private static Long toLongOrNull(Object value) {
		if (value instanceof Long longValue) {
			return longValue;
		}
		if (value instanceof Number number) {
			double candidate = number.doubleValue();
			if (!Double.isFinite(candidate) || candidate % 1.0d != 0.0d) {
				return null;
			}
			return (long) candidate;
		}
		if (value instanceof String text) {
			try {
				return Long.parseLong(text.trim());
			} catch (Exception ignored) {
				return null;
			}
		}
		return null;
	}

	private static Boolean toBooleanOrNull(Object value) {
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value instanceof String text) {
			String normalized = text.trim().toLowerCase();
			if ("true".equals(normalized)) {
				return Boolean.TRUE;
			}
			if ("false".equals(normalized)) {
				return Boolean.FALSE;
			}
		}
		return null;
	}

	private static void setOptionalString(StringSetter setter, String value) {
		if (value != null) {
			setter.apply(value);
		}
	}

	private static void setOptionalInt(IntSetter setter, Integer value) {
		if (value != null) {
			setter.apply(value);
		}
	}

	private static void setOptionalLong(LongSetter setter, Long value) {
		if (value != null) {
			setter.apply(value);
		}
	}

	private static void setOptionalDouble(DoubleSetter setter, Double value) {
		if (value != null) {
			setter.apply(value);
		}
	}

	private static void setOptionalBool(BoolSetter setter, Boolean value) {
		if (value != null) {
			setter.apply(value);
		}
	}

	@FunctionalInterface
	private interface StringSetter {
		void apply(String value);
	}

	@FunctionalInterface
	private interface IntSetter {
		void apply(int value);
	}

	@FunctionalInterface
	private interface LongSetter {
		void apply(long value);
	}

	@FunctionalInterface
	private interface DoubleSetter {
		void apply(double value);
	}

	@FunctionalInterface
	private interface BoolSetter {
		void apply(boolean value);
	}
}
