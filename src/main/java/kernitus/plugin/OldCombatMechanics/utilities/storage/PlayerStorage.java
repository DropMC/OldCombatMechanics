/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package kernitus.plugin.OldCombatMechanics.utilities.storage;

import kernitus.plugin.OldCombatMechanics.OCMMain;
import org.bson.*;
import org.bson.codecs.*;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import kernitus.plugin.OldCombatMechanics.utilities.scheduling.OCMScheduler;
import kernitus.plugin.OldCombatMechanics.utilities.scheduling.TaskHandle;
import org.bson.io.BasicOutputBuffer;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * Stores data associated with players to disk, persisting across server restarts.
 */
public class PlayerStorage {

    private static OCMMain plugin;
    private static Path dataFilePath;
    private static DocumentCodec documentCodec;
    // Guards every read and write of data, which the async save encodes while tick threads update it.
    private static final Object DATA_LOCK = new Object();
    // Serialises encode and write, so an older snapshot never overwrites a newer one on disk.
    private static final Object WRITE_LOCK = new Object();
    private static Document data;
    private static final AtomicReference<TaskHandle> saveTask = new AtomicReference<>();
    private static CodecRegistry codecRegistry;
    private static final Map<UUID, Map<UUID, String>> modesetCache = new ConcurrentHashMap<>();

    public static void initialise(OCMMain plugin) {
        PlayerStorage.plugin = plugin;
        dataFilePath = Paths.get(plugin.getDataFolder() + File.separator + "players.bson");

        codecRegistry = CodecRegistries.fromRegistries(
                CodecRegistries.fromCodecs(new DocumentCodec()), // Explicitly provide a DocumentCodec
                CodecRegistries.fromCodecs(new PlayerDataCodec()),
                CodecRegistries.fromProviders(new BsonValueCodecProvider(), new ValueCodecProvider()) // For BSON values
        );

        PlayerStorage.documentCodec = new DocumentCodec(codecRegistry);

        final Document loaded = loadData();
        synchronized (DATA_LOCK) {
            data = loaded;
        }
        modesetCache.clear();

        saveTask.set(null);
    }

    private static Document loadData() {
        if (Files.notExists(dataFilePath)) return new Document();

        try {
            byte[] data = Files.readAllBytes(dataFilePath);
            final BsonReader reader = new BsonBinaryReader(ByteBuffer.wrap(data));
            return documentCodec.decode(reader, DecoderContext.builder().build());
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Error loading player data", e);
        }
        return new Document();
    }

    public static void scheduleSave() {
        // Schedule a task for later, if there isn't one already scheduled
        if (saveTask.get() != null) return;
        final TaskHandle task = OCMScheduler.runAsyncAfterTicks(2400L, () -> { // Save after 2 minutes
            saveTask.set(null);
            instantSave();
        });
        if (!saveTask.compareAndSet(null, task)) task.cancel();
    }

    public static void instantSave() {
        synchronized (WRITE_LOCK) {
            final BasicOutputBuffer outputBuffer = new BasicOutputBuffer();
            try {
                final BsonWriter writer = new BsonBinaryWriter(outputBuffer);
                synchronized (DATA_LOCK) {
                    documentCodec.encode(writer, data, EncoderContext.builder().isEncodingCollectibleDocument(true).build());
                }
                writer.flush();
                Files.write(dataFilePath, outputBuffer.toByteArray());
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Error saving player data", e);
            } finally {
                outputBuffer.close();
            }
        }
    }

    public static PlayerData getPlayerData(UUID uuid) {
        final Document playerDoc;
        synchronized (DATA_LOCK) {
            playerDoc = (Document) data.get(uuid.toString());
        }
        if (playerDoc == null) {
            final PlayerData playerData = new PlayerData();
            setPlayerData(uuid, playerData);
            scheduleSave();
            return playerData;
        }
        final BsonDocument bsonDocument = new BsonDocumentWrapper<>(playerDoc, documentCodec);
        return codecRegistry.get(PlayerData.class).decode(bsonDocument.asBsonReader(), DecoderContext.builder().build());
    }

    public static @Nullable String getModesetForWorld(UUID uuid, UUID worldId) {
        Map<UUID, String> modesets = modesetCache.get(uuid);
        if (modesets == null) {
            final Map<UUID, String> loaded = snapshot(getPlayerData(uuid));
            final Map<UUID, String> existing = modesetCache.putIfAbsent(uuid, loaded);
            modesets = existing != null ? existing : loaded;
        }
        return modesets.get(worldId);
    }

    public static void forget(UUID uuid) {
        modesetCache.remove(uuid);
    }

    private static Map<UUID, String> snapshot(PlayerData playerData) {
        return Collections.unmodifiableMap(new HashMap<>(playerData.getModesetByWorld()));
    }

    public static void setPlayerData(UUID uuid, PlayerData playerData) {
        // Create a BsonDocumentWriter to hold the encoded data
        BsonDocumentWriter writer = new BsonDocumentWriter(new BsonDocument());

        // Get the PlayerDataCodec from the CodecRegistry
        PlayerDataCodec playerDataCodec = (PlayerDataCodec) codecRegistry.get(PlayerData.class);

        // Encode the PlayerData object to the writer
        playerDataCodec.encode(writer, playerData, EncoderContext.builder().isEncodingCollectibleDocument(true).build());

        // Retrieve the BsonDocument
        BsonDocument bsonDocument = writer.getDocument();

        // Convert the BsonDocument to a Document
        Document document = new Document();
        bsonDocument.forEach((key, value) -> document.put(key, value.isDocument() ? new Document(value.asDocument()) : value));

        // Put the Document into your data map
        synchronized (DATA_LOCK) {
            data.put(uuid.toString(), document);
        }
        modesetCache.put(uuid, snapshot(playerData));
    }
}