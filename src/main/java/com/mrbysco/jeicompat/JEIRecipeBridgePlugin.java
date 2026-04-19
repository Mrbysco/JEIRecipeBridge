package com.mrbysco.jeicompat;

import com.mrbysco.jeicompat.compat.fabric.FabricSupportedRecipeSerializersPayload;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JEIRecipeBridgePlugin extends JavaPlugin {
	public static final Logger LOGGER = LoggerFactory.getLogger("JEIRecipeBridge");
	public static Plugin Plugin;

	@Override
	public void onEnable() {
		Plugin = this;
		saveDefaultConfig();

		RecipeHandler recipeHandler = new RecipeHandler(this);
		getServer().getPluginManager().registerEvents(recipeHandler, this);

		final Server server = getServer();
		final Messenger messenger = server.getMessenger();
		// Register plugin channels for outgoing messages with the ids used by NeoForge and Fabric
		messenger.registerOutgoingPluginChannel(this, "neoforge:recipe_content");
		messenger.registerOutgoingPluginChannel(this, "fabric:recipe_sync");
		messenger.registerIncomingPluginChannel(this, FabricSupportedRecipeSerializersPayload.CHANNEL, recipeHandler);
	}

	@Override
	public void onDisable() {
		// Plugin shutdown logic
	}
}
