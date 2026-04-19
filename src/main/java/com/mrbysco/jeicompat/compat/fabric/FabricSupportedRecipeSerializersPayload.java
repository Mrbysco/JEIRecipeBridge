package com.mrbysco.jeicompat.compat.fabric;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

import java.util.HashSet;
import java.util.Set;

public final class FabricSupportedRecipeSerializersPayload {
	public static final String CHANNEL = "fabric:recipe_sync/supported_serializers";
	private static final int MAX_SERIALIZER_COUNT = 4096;

	private FabricSupportedRecipeSerializersPayload() {
	}

	public static Set<Identifier> decode(byte[] data) {
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
		try {
			int count = buffer.readVarInt();
			if (count < 0 || count > MAX_SERIALIZER_COUNT) {
				throw new IllegalArgumentException("Invalid synchronized serializer count: " + count);
			}

			var serializers = new HashSet<Identifier>(Math.max(16, count));
			for (int index = 0; index < count; index++) {
				serializers.add(buffer.readIdentifier());
			}
			return Set.copyOf(serializers);
		} finally {
			buffer.release();
		}
	}
}
