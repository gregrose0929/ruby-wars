package dev.rubymod.net;

import java.util.List;

import dev.rubymod.RubyMod;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Packets for the map, waypoints, squads, look-at info and research. */
public final class MorePayloads {
	private MorePayloads() {
	}

	/** A map marker. kind: 0 waypoint, 1 grave, 2 ping, 3 supply drop, 4 your Heart, 5 squadmate's Heart, 6 raid, 7 spotted enemy. */
	public record Marker(String name, int x, int y, int z, byte kind) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Marker> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, Marker::name,
				ByteBufCodecs.VAR_INT, Marker::x,
				ByteBufCodecs.VAR_INT, Marker::y,
				ByteBufCodecs.VAR_INT, Marker::z,
				ByteBufCodecs.BYTE, Marker::kind,
				Marker::new);
	}

	/** A squadmate: where they are and how healthy (0..1). */
	public record Mate(String name, float x, float y, float z, float health) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Mate> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, Mate::name,
				ByteBufCodecs.FLOAT, Mate::x,
				ByteBufCodecs.FLOAT, Mate::y,
				ByteBufCodecs.FLOAT, Mate::z,
				ByteBufCodecs.FLOAT, Mate::health,
				Mate::new);
	}

	/** A claim on the map. relation: 0 yours, 1 your squad's, 2 someone else's. */
	public record ClaimInfo(String name, int x, int z, int radius, byte relation) {
		public static final StreamCodec<RegistryFriendlyByteBuf, ClaimInfo> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, ClaimInfo::name,
				ByteBufCodecs.VAR_INT, ClaimInfo::x,
				ByteBufCodecs.VAR_INT, ClaimInfo::z,
				ByteBufCodecs.VAR_INT, ClaimInfo::radius,
				ByteBufCodecs.BYTE, ClaimInfo::relation,
				ClaimInfo::new);
	}

	/** Server -> client, every second: everything the minimap and waypoint markers show. */
	public record MapSync(String squad, List<Marker> markers, List<Mate> mates, List<ClaimInfo> claims) implements CustomPacketPayload {
		public static final Type<MapSync> TYPE = new Type<>(RubyMod.id("map_sync"));
		public static final StreamCodec<RegistryFriendlyByteBuf, MapSync> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, MapSync::squad,
				Marker.CODEC.apply(ByteBufCodecs.list()), MapSync::markers,
				Mate.CODEC.apply(ByteBufCodecs.list()), MapSync::mates,
				ClaimInfo.CODEC.apply(ByteBufCodecs.list()), MapSync::claims,
				MapSync::new);

		@Override
		public Type<MapSync> type() {
			return TYPE;
		}
	}

	/** Client -> server: ping this spot for your squad. */
	public record Ping(int x, int y, int z, int entityId) implements CustomPacketPayload {
		public static final Type<Ping> TYPE = new Type<>(RubyMod.id("ping"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Ping> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Ping::x, ByteBufCodecs.VAR_INT, Ping::y, ByteBufCodecs.VAR_INT, Ping::z, ByteBufCodecs.VAR_INT, Ping::entityId,
				Ping::new);

		@Override
		public Type<Ping> type() {
			return TYPE;
		}
	}

	/** Client -> server: what's this? (a block, or an entity when entityId >= 0). */
	public record LookQuery(BlockPos pos, int entityId) implements CustomPacketPayload {
		public static final Type<LookQuery> TYPE = new Type<>(RubyMod.id("look_query"));
		public static final StreamCodec<RegistryFriendlyByteBuf, LookQuery> CODEC = StreamCodec.composite(
				BlockPos.STREAM_CODEC, LookQuery::pos, ByteBufCodecs.VAR_INT, LookQuery::entityId, LookQuery::new);

		@Override
		public Type<LookQuery> type() {
			return TYPE;
		}
	}

	/** Server -> client: lines for the look-at panel (first line is the title). bar: 0..1 health/charge bar, or -1. */
	public record LookInfo(BlockPos pos, int entityId, List<String> lines, float bar, int color) implements CustomPacketPayload {
		public static final Type<LookInfo> TYPE = new Type<>(RubyMod.id("look_info"));
		public static final StreamCodec<RegistryFriendlyByteBuf, LookInfo> CODEC = StreamCodec.composite(
				BlockPos.STREAM_CODEC, LookInfo::pos,
				ByteBufCodecs.VAR_INT, LookInfo::entityId,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), LookInfo::lines,
				ByteBufCodecs.FLOAT, LookInfo::bar,
				ByteBufCodecs.INT, LookInfo::color,
				LookInfo::new);

		@Override
		public Type<LookInfo> type() {
			return TYPE;
		}
	}

	/** Server -> client: research points to spend and what's unlocked. */
	public record ResearchSync(int points, List<String> unlocked) implements CustomPacketPayload {
		public static final Type<ResearchSync> TYPE = new Type<>(RubyMod.id("research_sync"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ResearchSync> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, ResearchSync::points,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ResearchSync::unlocked,
				ResearchSync::new);

		@Override
		public Type<ResearchSync> type() {
			return TYPE;
		}
	}

	/** Client -> server: unlock a research node. */
	public record ResearchUnlock(String node) implements CustomPacketPayload {
		public static final Type<ResearchUnlock> TYPE = new Type<>(RubyMod.id("research_unlock"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ResearchUnlock> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, ResearchUnlock::node, ResearchUnlock::new);

		@Override
		public Type<ResearchUnlock> type() {
			return TYPE;
		}
	}

	/** Server -> client: a bullet hole at this point on a block face (size: 0 small, 1 normal, 2 large). */
	public record BulletHole(float x, float y, float z, byte face, byte size) implements CustomPacketPayload {
		public static final Type<BulletHole> TYPE = new Type<>(RubyMod.id("bullet_hole"));
		public static final StreamCodec<RegistryFriendlyByteBuf, BulletHole> CODEC = StreamCodec.composite(
				ByteBufCodecs.FLOAT, BulletHole::x, ByteBufCodecs.FLOAT, BulletHole::y, ByteBufCodecs.FLOAT, BulletHole::z,
				ByteBufCodecs.BYTE, BulletHole::face, ByteBufCodecs.BYTE, BulletHole::size, BulletHole::new);

		@Override
		public Type<BulletHole> type() {
			return TYPE;
		}
	}

	/** Server -> client: you're in combat for this many seconds (don't log out). */
	public record CombatTagged(int seconds) implements CustomPacketPayload {
		public static final Type<CombatTagged> TYPE = new Type<>(RubyMod.id("combat_tagged"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CombatTagged> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, CombatTagged::seconds, CombatTagged::new);

		@Override
		public Type<CombatTagged> type() {
			return TYPE;
		}
	}

	/** Server -> client, every second during a raid on your side: raiders left, seconds left, rubies looted (0 raiders = over). */
	public record RaidStatus(int raiders, int seconds, int looted) implements CustomPacketPayload {
		public static final Type<RaidStatus> TYPE = new Type<>(RubyMod.id("raid_status"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RaidStatus> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, RaidStatus::raiders, ByteBufCodecs.VAR_INT, RaidStatus::seconds, ByteBufCodecs.VAR_INT, RaidStatus::looted,
				RaidStatus::new);

		@Override
		public Type<RaidStatus> type() {
			return TYPE;
		}
	}

	/** Server -> client: today's bounties as display lines ("Kill 20 monsters|12|20|3|done?"). */
	public record BountySync(List<String> lines) implements CustomPacketPayload {
		public static final Type<BountySync> TYPE = new Type<>(RubyMod.id("bounty_sync"));
		public static final StreamCodec<RegistryFriendlyByteBuf, BountySync> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), BountySync::lines, BountySync::new);

		@Override
		public Type<BountySync> type() {
			return TYPE;
		}
	}

	/** Client -> server: autopilot on (holding this heading and altitude) or off. */
	public record Autopilot(boolean on, float heading, float altitude) implements CustomPacketPayload {
		public static final Type<Autopilot> TYPE = new Type<>(RubyMod.id("autopilot"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Autopilot> CODEC = StreamCodec.composite(
				ByteBufCodecs.BOOL, Autopilot::on, ByteBufCodecs.FLOAT, Autopilot::heading, ByteBufCodecs.FLOAT, Autopilot::altitude, Autopilot::new);

		@Override
		public Type<Autopilot> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(Autopilot.TYPE, Autopilot.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BountySync.TYPE, BountySync.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RaidStatus.TYPE, RaidStatus.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CombatTagged.TYPE, CombatTagged.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BulletHole.TYPE, BulletHole.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Ping.TYPE, Ping.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(LookQuery.TYPE, LookQuery.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ResearchUnlock.TYPE, ResearchUnlock.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MapSync.TYPE, MapSync.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LookInfo.TYPE, LookInfo.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ResearchSync.TYPE, ResearchSync.CODEC);
	}
}
