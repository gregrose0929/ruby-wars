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
	/** Stars to spend, unlocked nodes, and goals as "id|title|progress|target|stars|done". */
	public record ResearchSync(int points, List<String> unlocked, List<String> goals) implements CustomPacketPayload {
		public static final Type<ResearchSync> TYPE = new Type<>(RubyMod.id("research_sync"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ResearchSync> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, ResearchSync::points,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ResearchSync::unlocked,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ResearchSync::goals,
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

	/** Client -> server: switch game ("rubywars", "horde", "hub"), or "" just to ask for the game picker's info. */
	public record ModeSelect(String mode) implements CustomPacketPayload {
		public static final Type<ModeSelect> TYPE = new Type<>(RubyMod.id("mode_select"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ModeSelect> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, ModeSelect::mode, ModeSelect::new);

		@Override
		public Type<ModeSelect> type() {
			return TYPE;
		}
	}

	/** Server -> client: the Horde Armory: team bank, waves cleared and "id|category|name|icon|price|wave|about" per item (open: show the screen). */
	public record HordeShopSync(boolean open, int bank, int cleared, List<String> entries) implements CustomPacketPayload {
		public static final Type<HordeShopSync> TYPE = new Type<>(RubyMod.id("horde_shop"));
		public static final StreamCodec<RegistryFriendlyByteBuf, HordeShopSync> CODEC = StreamCodec.composite(
				ByteBufCodecs.BOOL, HordeShopSync::open,
				ByteBufCodecs.VAR_INT, HordeShopSync::bank,
				ByteBufCodecs.VAR_INT, HordeShopSync::cleared,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), HordeShopSync::entries,
				HordeShopSync::new);

		@Override
		public Type<HordeShopSync> type() {
			return TYPE;
		}
	}

	/** Server -> client: open the game picker (walking into the hub's Party Games portal). */
	public record OpenModes() implements CustomPacketPayload {
		public static final Type<OpenModes> TYPE = new Type<>(RubyMod.id("open_modes"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenModes> CODEC = StreamCodec.unit(new OpenModes());

		@Override
		public Type<OpenModes> type() {
			return TYPE;
		}
	}

	/** Client -> server: send me my profile. */
	public record ProfileRequest() implements CustomPacketPayload {
		public static final Type<ProfileRequest> TYPE = new Type<>(RubyMod.id("profile_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ProfileRequest> CODEC = StreamCodec.unit(new ProfileRequest());

		@Override
		public Type<ProfileRequest> type() {
			return TYPE;
		}
	}

	/** Server -> client: profile stats ("label|value") and cosmetics ("id|kind|name|icon|how|unlocked|wearing"). */
	public record ProfileSync(List<String> stats, List<String> cosmetics) implements CustomPacketPayload {
		public static final Type<ProfileSync> TYPE = new Type<>(RubyMod.id("profile_sync"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ProfileSync> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ProfileSync::stats,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ProfileSync::cosmetics,
				ProfileSync::new);

		@Override
		public Type<ProfileSync> type() {
			return TYPE;
		}
	}

	/** Client -> server: wear a cosmetic ("none:HAT" etc. to take one off). */
	public record CosmeticSelect(String id) implements CustomPacketPayload {
		public static final Type<CosmeticSelect> TYPE = new Type<>(RubyMod.id("cosmetic_select"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CosmeticSelect> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, CosmeticSelect::id, CosmeticSelect::new);

		@Override
		public Type<CosmeticSelect> type() {
			return TYPE;
		}
	}

	/** Server -> client: the Sky Race minimap: ring x,z pairs, the next ring, other racers' x,z pairs. */
	public record RaceMap(List<Integer> rings, int next, List<Integer> racers) implements CustomPacketPayload {
		public static final Type<RaceMap> TYPE = new Type<>(RubyMod.id("race_map"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RaceMap> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), RaceMap::rings,
				ByteBufCodecs.VAR_INT, RaceMap::next,
				ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), RaceMap::racers,
				RaceMap::new);

		@Override
		public Type<RaceMap> type() {
			return TYPE;
		}
	}

	/** Server -> client: open (or refresh) a Ruby Rift screen: "map", "offer", "shop", "event", or "close". */
	public record RiftScreen(String kind, List<String> lines) implements CustomPacketPayload {
		public static final Type<RiftScreen> TYPE = new Type<>(RubyMod.id("rift_screen"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RiftScreen> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, RiftScreen::kind,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), RiftScreen::lines,
				RiftScreen::new);

		@Override
		public Type<RiftScreen> type() {
			return TYPE;
		}
	}

	/** Client -> server: a Ruby Rift choice: "vote" (map node), "pick" (relic), "buy", "reroll", "event", "ability" (jump, blink). */
	public record RiftChoice(String kind, String id) implements CustomPacketPayload {
		public static final Type<RiftChoice> TYPE = new Type<>(RubyMod.id("rift_choice"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RiftChoice> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, RiftChoice::kind,
				ByteBufCodecs.STRING_UTF8, RiftChoice::id,
				RiftChoice::new);

		@Override
		public Type<RiftChoice> type() {
			return TYPE;
		}
	}

	/** Server -> client: the Ruby Rift HUD: a header line, the team's gold, your relics ("id|icon|TIER") and the team ("name|hp|max|down"). Empty header: no HUD. */
	public record RiftHud(String header, int gold, List<String> relics, List<String> team) implements CustomPacketPayload {
		public static final Type<RiftHud> TYPE = new Type<>(RubyMod.id("rift_hud"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RiftHud> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, RiftHud::header,
				ByteBufCodecs.VAR_INT, RiftHud::gold,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), RiftHud::relics,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), RiftHud::team,
				RiftHud::new);

		@Override
		public Type<RiftHud> type() {
			return TYPE;
		}
	}

	/** Server -> client: a floating damage number where you hit something. */
	public record DamageNumber(float x, float y, float z, float amount, int color) implements CustomPacketPayload {
		public static final Type<DamageNumber> TYPE = new Type<>(RubyMod.id("damage_number"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DamageNumber> CODEC = StreamCodec.composite(
				ByteBufCodecs.FLOAT, DamageNumber::x,
				ByteBufCodecs.FLOAT, DamageNumber::y,
				ByteBufCodecs.FLOAT, DamageNumber::z,
				ByteBufCodecs.FLOAT, DamageNumber::amount,
				ByteBufCodecs.INT, DamageNumber::color,
				DamageNumber::new);

		@Override
		public Type<DamageNumber> type() {
			return TYPE;
		}
	}

	/** Server -> client: a screen effect: "flash" (a colour wash), "slowmo" (a cold vignette), "rewind", "relic:<item id>" (the big item pop). */
	public record ScreenFx(String kind, int color, int ticks) implements CustomPacketPayload {
		public static final Type<ScreenFx> TYPE = new Type<>(RubyMod.id("screen_fx"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ScreenFx> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, ScreenFx::kind,
				ByteBufCodecs.INT, ScreenFx::color,
				ByteBufCodecs.VAR_INT, ScreenFx::ticks,
				ScreenFx::new);

		@Override
		public Type<ScreenFx> type() {
			return TYPE;
		}
	}

	/** Client -> server: buy this from the Horde Armory. */
	public record HordeBuy(String id) implements CustomPacketPayload {
		public static final Type<HordeBuy> TYPE = new Type<>(RubyMod.id("horde_buy"));
		public static final StreamCodec<RegistryFriendlyByteBuf, HordeBuy> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, HordeBuy::id, HordeBuy::new);

		@Override
		public Type<HordeBuy> type() {
			return TYPE;
		}
	}

	/** Server -> client: where you are (Hub.Where) and one "id|title|status" line per game, for the game picker. */
	public record ModeInfo(String where, List<String> modes) implements CustomPacketPayload {
		public static final Type<ModeInfo> TYPE = new Type<>(RubyMod.id("mode_info"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ModeInfo> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, ModeInfo::where,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ModeInfo::modes,
				ModeInfo::new);

		@Override
		public Type<ModeInfo> type() {
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

	/** Client to server: the jetpack started or stopped firing. */
	public record JetpackThrust(boolean on) implements CustomPacketPayload {
		public static final Type<JetpackThrust> TYPE = new Type<>(RubyMod.id("jetpack_thrust"));
		public static final StreamCodec<RegistryFriendlyByteBuf, JetpackThrust> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, JetpackThrust::on, JetpackThrust::new);

		@Override
		public Type<JetpackThrust> type() {
			return TYPE;
		}
	}

	/** Client to server on join: which build of the mod this player has. */
	public record Hello(String build) implements CustomPacketPayload {
		public static final Type<Hello> TYPE = new Type<>(RubyMod.id("hello"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Hello::build, Hello::new);

		@Override
		public Type<Hello> type() {
			return TYPE;
		}
	}

	/** Server to a player who just died: who got them, with what, from how far (killerId for the killcam, -1 if none). */
	public record DeathInfo(String killer, String weapon, int distance, int killerId, boolean headshot) implements CustomPacketPayload {
		public static final Type<DeathInfo> TYPE = new Type<>(RubyMod.id("death_info"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DeathInfo> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, DeathInfo::killer, ByteBufCodecs.STRING_UTF8, DeathInfo::weapon, ByteBufCodecs.VAR_INT, DeathInfo::distance,
				ByteBufCodecs.VAR_INT, DeathInfo::killerId, ByteBufCodecs.BOOL, DeathInfo::headshot, DeathInfo::new);

		@Override
		public Type<DeathInfo> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(Hello.TYPE, Hello.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(JetpackThrust.TYPE, JetpackThrust.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DeathInfo.TYPE, DeathInfo.CODEC);
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
		PayloadTypeRegistry.serverboundPlay().register(ModeSelect.TYPE, ModeSelect.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ModeInfo.TYPE, ModeInfo.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(HordeShopSync.TYPE, HordeShopSync.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(OpenModes.TYPE, OpenModes.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ProfileRequest.TYPE, ProfileRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ProfileSync.TYPE, ProfileSync.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CosmeticSelect.TYPE, CosmeticSelect.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(HordeBuy.TYPE, HordeBuy.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RaceMap.TYPE, RaceMap.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RiftScreen.TYPE, RiftScreen.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RiftChoice.TYPE, RiftChoice.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RiftHud.TYPE, RiftHud.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DamageNumber.TYPE, DamageNumber.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ScreenFx.TYPE, ScreenFx.CODEC);
	}
}
