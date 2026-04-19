package com.mrbysco.jeicompat;

import com.mrbysco.jeicompat.compat.fabric.FabricRecipeSyncPayload;
import com.mrbysco.jeicompat.compat.fabric.FabricSupportedRecipeSerializersPayload;
import com.mrbysco.jeicompat.compat.neoforge.NeoforgeRecipeSyncPayload;
import io.netty.buffer.Unpooled;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerConnection;
import io.papermc.paper.event.connection.configuration.PlayerConnectionInitialConfigureEvent;
import io.papermc.paper.event.connection.configuration.PlayerConnectionReconfigureEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RecipeHandler implements Listener, PluginMessageListener {
	private final JEIRecipeBridgePlugin plugin;
	private final ConcurrentHashMap<UUID, Set<Identifier>> supportedFabricRecipeSerializers = new ConcurrentHashMap<>();

	public RecipeHandler(JEIRecipeBridgePlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler
	public void onInitialConfigure(PlayerConnectionInitialConfigureEvent event) {
		clearSupportedFabricRecipeSerializers(event.getConnection());
	}

	@EventHandler
	public void onReconfigure(PlayerConnectionReconfigureEvent event) {
		clearSupportedFabricRecipeSerializers(event.getConnection());
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		final Player originalPlayer = event.getPlayer();
		final ServerPlayer player = ((CraftPlayer) originalPlayer).getHandle();
		final MinecraftServer server = player.level().getServer();
		final RecipeManager recipeManager = server.getRecipeManager();
		final String brand = originalPlayer.getClientBrandName();
		if (brand == null) {
			return; // Unknown brand, do not send any custom payload
		}

		final RecipeMap recipeMap = recipeManager.recipes;

		try {
			if (brand.equalsIgnoreCase("fabric")) {
				Set<Identifier> supportedSerializers = this.supportedFabricRecipeSerializers.remove(originalPlayer.getUniqueId());
				if (sendFabricPayload(player, server, recipeMap, supportedSerializers)) {
					sendSyncMessage(originalPlayer);
				}
			} else if (brand.equalsIgnoreCase("neoforge")) {
				if (sendNeoForgePayload(player, server, recipeMap)) {
					sendSyncMessage(originalPlayer);
				}
			}
		} catch (RuntimeException | LinkageError exception) {
			JEIRecipeBridgePlugin.LOGGER.error("Failed to sync JEI recipes to player '{}' with client brand '{}'", originalPlayer.getName(), brand, exception);
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		this.supportedFabricRecipeSerializers.remove(event.getPlayer().getUniqueId());
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		// Fabric sends supported serializers during the configuration phase, before a Bukkit Player exists.
	}

	@Override
	public void onPluginMessageReceived(String channel, PlayerConnection connection, byte[] message) {
		if (!FabricSupportedRecipeSerializersPayload.CHANNEL.equals(channel) || !(connection instanceof PlayerConfigurationConnection configurationConnection)) {
			return;
		}

		UUID uniqueId = configurationConnection.getProfile().getId();
		if (uniqueId == null) {
			return;
		}

		this.supportedFabricRecipeSerializers.put(uniqueId, FabricSupportedRecipeSerializersPayload.decode(message));
	}

	private void clearSupportedFabricRecipeSerializers(PlayerConfigurationConnection connection) {
		UUID uniqueId = connection.getProfile().getId();
		if (uniqueId != null) {
			this.supportedFabricRecipeSerializers.remove(uniqueId);
		}
	}

	private void sendSyncMessage(Player player) {
		if (plugin.getConfig().getBoolean("show-sync-message", false)) {
			player.sendMessage("JEIRecipeBridge: Syncing recipes...");
		}
	}

	private static boolean sendNeoForgePayload(ServerPlayer player, MinecraftServer server, RecipeMap recipeMap) {
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
		try {
			List<RecipeType<?>> allRecipeTypes = BuiltInRegistries.RECIPE_TYPE.stream().toList();
			var payload = NeoforgeRecipeSyncPayload.create(allRecipeTypes, recipeMap);
			NeoforgeRecipeSyncPayload.STREAM_CODEC.encode(buffer, payload);

			byte[] bytes = new byte[buffer.writerIndex()];
			buffer.getBytes(0, bytes);

			sendPayload(player, Identifier.fromNamespaceAndPath("neoforge", "recipe_content"), bytes);
			player.connection.send(new ClientboundUpdateTagsPacket(TagNetworkSerialization.serializeTagsToNetwork(server.registries())));
			return true;
		} finally {
			buffer.release();
		}
	}

	private static boolean sendFabricPayload(ServerPlayer player, MinecraftServer server, RecipeMap recipeMap, Set<Identifier> supportedSerializers) {
		if (supportedSerializers == null || supportedSerializers.isEmpty()) {
			return false;
		}

		var list = new ArrayList<FabricRecipeSyncPayload.Entry>();
		var seen = new HashSet<RecipeSerializer<?>>();

		for (RecipeSerializer<?> serializer : BuiltInRegistries.RECIPE_SERIALIZER) {
			if (!seen.add(serializer)) continue; // skip duplicates
			Identifier serializerId = BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer);
			if (serializerId == null || !supportedSerializers.contains(serializerId)) continue;

			List<RecipeHolder<?>> recipes = new ArrayList<>();
			for (RecipeHolder<?> holder : recipeMap.values()) {
				if (holder.value().getSerializer() == serializer) {
					recipes.add(holder);
				}
			}

			if (!recipes.isEmpty()) {
				RecipeSerializer<?> entrySerializer = recipes.get(0).value().getSerializer();
				list.add(new FabricRecipeSyncPayload.Entry(entrySerializer, recipes));
			}
		}

		if (list.isEmpty()) {
			return false;
		}

		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
		try {
			var payload = new FabricRecipeSyncPayload(list);
			FabricRecipeSyncPayload.CODEC.encode(buffer, payload);

			byte[] bytes = new byte[buffer.writerIndex()];
			buffer.getBytes(0, bytes);

			sendPayload(player, Identifier.fromNamespaceAndPath("fabric", "recipe_sync"), bytes);
			return true;
		} finally {
			buffer.release();
		}
	}

	private static void sendPayload(ServerPlayer player, Identifier id, byte[] bytes) {
		player.connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(id, bytes)));
	}
}
