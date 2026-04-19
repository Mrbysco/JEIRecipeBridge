package com.mrbysco.jeicompat.compat.fabric;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.SkipPacketDecoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeSerializer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public record FabricRecipeSyncPayload(List<Entry> entries) implements CustomPacketPayload {
	public static final StreamCodec<RegistryFriendlyByteBuf, FabricRecipeSyncPayload> CODEC = Entry.CODEC.apply(ByteBufCodecs.list())
			.map(FabricRecipeSyncPayload::new, FabricRecipeSyncPayload::entries);

	public static final Type<FabricRecipeSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("fabric", "recipe_sync"));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public record Entry(RecipeSerializer<?> serializer, List<RecipeHolder<?>> recipes) {
		private static final Method STREAM_CODEC_METHOD = resolveStreamCodecMethod();

		public static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC = StreamCodec.ofMember(
				Entry::write,
				Entry::read
		);

		private static Method resolveStreamCodecMethod() {
			try {
				return RecipeSerializer.class.getMethod("streamCodec");
			} catch (NoSuchMethodException exception) {
				throw new ExceptionInInitializerError(exception);
			}
		}

		@SuppressWarnings("unchecked")
		private static StreamCodec<RegistryFriendlyByteBuf, Recipe<?>> recipeStreamCodec(RecipeSerializer<?> serializer) {
			try {
				Object codec = STREAM_CODEC_METHOD.invoke(serializer);
				if (codec instanceof StreamCodec<?, ?> streamCodec) {
					return (StreamCodec<RegistryFriendlyByteBuf, Recipe<?>>) streamCodec;
				}
			} catch (IllegalAccessException exception) {
				throw new IllegalStateException("Cannot access stream codec for recipe serializer '" + BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer) + "'", exception);
			} catch (InvocationTargetException exception) {
				Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
				throw new IllegalStateException("Cannot get stream codec for recipe serializer '" + BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer) + "'", cause);
			}

			throw new IllegalStateException("Recipe serializer '" + BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer) + "' returned an invalid stream codec");
		}

		private static Entry read(RegistryFriendlyByteBuf buf) {
			Identifier recipeSerializerId = buf.readIdentifier();
			RecipeSerializer<?> recipeSerializer = BuiltInRegistries.RECIPE_SERIALIZER.getValue(recipeSerializerId);

			if (recipeSerializer == null) {
				throw new SkipPacketDecoderException("Tried syncing unsupported packet serializer '" + recipeSerializerId + "'!");
			}

			int count = buf.readVarInt();
			var list = new ArrayList<RecipeHolder<?>>();

			for (int i = 0; i < count; i++) {
				ResourceKey<Recipe<?>> id = buf.readResourceKey(Registries.RECIPE);
				Recipe<?> recipe = recipeStreamCodec(recipeSerializer).decode(buf);
				list.add(new RecipeHolder<>(id, recipe));
			}

			return new Entry(recipeSerializer, list);
		}

		private void write(RegistryFriendlyByteBuf buf) {
			buf.writeIdentifier(BuiltInRegistries.RECIPE_SERIALIZER.getKey(this.serializer));

			buf.writeVarInt(this.recipes.size());

			StreamCodec<RegistryFriendlyByteBuf, Recipe<?>> serializer = recipeStreamCodec(this.serializer);

			for (RecipeHolder<?> recipe : this.recipes) {
				buf.writeResourceKey(recipe.id());
				serializer.encode(buf, recipe.value());
			}
		}
	}
}
