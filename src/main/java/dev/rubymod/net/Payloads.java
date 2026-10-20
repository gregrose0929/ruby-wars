package dev.rubymod.net;

import dev.rubymod.RubyMod;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Every packet the mod sends between client and server. */
public class Payloads {
	/** Client -> server: the player pressed the reload key. */
	public record Reload() implements CustomPacketPayload {
		public static final Type<Reload> TYPE = new Type<>(RubyMod.id("reload"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Reload> CODEC = StreamCodec.unit(new Reload());

		@Override
		public Type<Reload> type() {
			return TYPE;
		}
	}

	/** Client -> server: the tank driver pressed attack (fire the main cannon). */
	public record VehicleFire() implements CustomPacketPayload {
		public static final Type<VehicleFire> TYPE = new Type<>(RubyMod.id("vehicle_fire"));
		public static final StreamCodec<RegistryFriendlyByteBuf, VehicleFire> CODEC = StreamCodec.unit(new VehicleFire());

		@Override
		public Type<VehicleFire> type() {
			return TYPE;
		}
	}

	/** Server -> client: kick the camera after a shot. */
	public record Recoil(float pitch, float yaw) implements CustomPacketPayload {
		public static final Type<Recoil> TYPE = new Type<>(RubyMod.id("recoil"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Recoil> CODEC = StreamCodec.composite(
				ByteBufCodecs.FLOAT, Recoil::pitch,
				ByteBufCodecs.FLOAT, Recoil::yaw,
				Recoil::new);

		@Override
		public Type<Recoil> type() {
			return TYPE;
		}
	}

	/** Server -> client: a reload started (ticks > 0) or was cancelled (ticks = 0). */
	public record ReloadState(int ticks) implements CustomPacketPayload {
		public static final Type<ReloadState> TYPE = new Type<>(RubyMod.id("reload_state"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ReloadState> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, ReloadState::ticks,
				ReloadState::new);

		@Override
		public Type<ReloadState> type() {
			return TYPE;
		}
	}

	/** Server -> client: your shot hit something. */
	public record HitMarker(boolean headshot, boolean kill) implements CustomPacketPayload {
		public static final Type<HitMarker> TYPE = new Type<>(RubyMod.id("hit_marker"));
		public static final StreamCodec<RegistryFriendlyByteBuf, HitMarker> CODEC = StreamCodec.composite(
				ByteBufCodecs.BOOL, HitMarker::headshot,
				ByteBufCodecs.BOOL, HitMarker::kill,
				HitMarker::new);

		@Override
		public Type<HitMarker> type() {
			return TYPE;
		}
	}

	/**
	 * Server -> client, once a second: what the HUD shows.
	 *
	 * @param phase        Phase ordinal
	 * @param secondsLeft  seconds until the phase ends, -1 if untimed
	 * @param paused       the season timer is paused
	 * @param day          build phase day number (1-14), 0 outside the build phase
	 * @param heartHp      your Heart's HP, -1 if you have no Heart
	 * @param heartMaxHp   your Heart's max HP
	 * @param alive        players still in the game (Battle Day)
	 * @param eliminated   you have been eliminated
	 */
	public record HudSync(int phase, int secondsLeft, boolean paused, int day, int heartHp, int heartMaxHp, int alive, boolean eliminated)
			implements CustomPacketPayload {
		public static final Type<HudSync> TYPE = new Type<>(RubyMod.id("hud_sync"));
		public static final StreamCodec<RegistryFriendlyByteBuf, HudSync> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, HudSync::phase,
				ByteBufCodecs.INT, HudSync::secondsLeft,
				ByteBufCodecs.BOOL, HudSync::paused,
				ByteBufCodecs.VAR_INT, HudSync::day,
				ByteBufCodecs.INT, HudSync::heartHp,
				ByteBufCodecs.VAR_INT, HudSync::heartMaxHp,
				ByteBufCodecs.VAR_INT, HudSync::alive,
				ByteBufCodecs.BOOL, HudSync::eliminated,
				HudSync::new);

		@Override
		public Type<HudSync> type() {
			return TYPE;
		}
	}

	/**
	 * Server -> client: radar contacts and alerts for players who own a Radar Station.
	 *
	 * @param contacts      enemies within radar range
	 * @param nearest       distance to the nearest contact in blocks, -1 if none
	 * @param bearing       compass bearing to the nearest contact in degrees
	 * @param missileAlert  an enemy missile or mortar shell is headed at your claim
	 * @param intruder      an Alarm Block in your claim was tripped
	 */
	public record Radar(int contacts, int nearest, int bearing, boolean missileAlert, boolean intruder) implements CustomPacketPayload {
		public static final Type<Radar> TYPE = new Type<>(RubyMod.id("radar"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Radar> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Radar::contacts,
				ByteBufCodecs.INT, Radar::nearest,
				ByteBufCodecs.INT, Radar::bearing,
				ByteBufCodecs.BOOL, Radar::missileAlert,
				ByteBufCodecs.BOOL, Radar::intruder,
				Radar::new);

		@Override
		public Type<Radar> type() {
			return TYPE;
		}
	}

	/** Server -> client: someone was killed by a player. */
	public record KillFeed(String killer, String victim, String weapon, boolean headshot) implements CustomPacketPayload {
		public static final Type<KillFeed> TYPE = new Type<>(RubyMod.id("kill_feed"));
		public static final StreamCodec<RegistryFriendlyByteBuf, KillFeed> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, KillFeed::killer,
				ByteBufCodecs.STRING_UTF8, KillFeed::victim,
				ByteBufCodecs.STRING_UTF8, KillFeed::weapon,
				ByteBufCodecs.BOOL, KillFeed::headshot,
				KillFeed::new);

		@Override
		public Type<KillFeed> type() {
			return TYPE;
		}
	}

	/** Server -> client: you took damage from something at this position (for the direction indicator). */
	public record DamageFrom(double x, double z) implements CustomPacketPayload {
		public static final Type<DamageFrom> TYPE = new Type<>(RubyMod.id("damage_from"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DamageFrom> CODEC = StreamCodec.composite(
				ByteBufCodecs.DOUBLE, DamageFrom::x,
				ByteBufCodecs.DOUBLE, DamageFrom::z,
				DamageFrom::new);

		@Override
		public Type<DamageFrom> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(Reload.TYPE, Reload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(VehicleFire.TYPE, VehicleFire.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Recoil.TYPE, Recoil.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ReloadState.TYPE, ReloadState.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(HitMarker.TYPE, HitMarker.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(HudSync.TYPE, HudSync.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Radar.TYPE, Radar.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(KillFeed.TYPE, KillFeed.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DamageFrom.TYPE, DamageFrom.CODEC);
	}
}
