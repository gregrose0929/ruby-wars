package dev.rubymod.voice;

import java.util.UUID;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;

import dev.rubymod.item.FieldRadioItem;
import dev.rubymod.registry.ModItems;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Simple Voice Chat plugin for the Field Radio: when someone talks while holding
 * a radio, everyone else carrying a radio on that channel hears them, at any range.
 * (Loaded only when Simple Voice Chat is installed.)
 */
public class RadioPlugin implements VoicechatPlugin {
	@Override
	public String getPluginId() {
		return "rubymod";
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
	}

	private void onMicrophone(MicrophonePacketEvent event) {
		VoicechatConnection sender = event.getSenderConnection();
		if (sender == null || !(sender.getPlayer().getPlayer() instanceof ServerPlayer speaker)) {
			return;
		}
		int channel = heldChannel(speaker);
		if (channel < 0) {
			return;
		}
		VoicechatServerApi api = event.getVoicechat();
		UUID id = speaker.getUUID();
		for (ServerPlayer listener : speaker.level().getServer().getPlayerList().getPlayers()) {
			if (listener.getUUID().equals(id) || carriedChannel(listener) != channel) {
				continue;
			}
			VoicechatConnection connection = api.getConnectionOf(listener.getUUID());
			if (connection != null && connection.isConnected()) {
				api.sendStaticSoundPacketTo(connection, event.getPacket().toStaticSoundPacket());
			}
		}
	}

	/** The channel of a radio in either hand, or -1. */
	private static int heldChannel(ServerPlayer player) {
		for (ItemStack stack : new ItemStack[] {player.getMainHandItem(), player.getOffhandItem()}) {
			if (stack.is(ModItems.FIELD_RADIO)) {
				return FieldRadioItem.channel(stack);
			}
		}
		return -1;
	}

	/** The channel of the first radio anywhere in the inventory, or -1. */
	private static int carriedChannel(ServerPlayer player) {
		var inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).is(ModItems.FIELD_RADIO)) {
				return FieldRadioItem.channel(inv.getItem(i));
			}
		}
		return -1;
	}
}
