package com.nstut.endless.vertical;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;

/** Immutable network snapshot of one sparse vertical page. */
public record VerticalPageSnapshot(
    int chunkX,
    int pageY,
    int chunkZ,
    long revision,
    List<SectionData> sections
) {
    private static final int MAX_SECTION_PAYLOAD = 1 << 20;
    private static final int MAX_PAGE_PAYLOAD = 8 << 20;

    public VerticalPageSnapshot {
        sections = List.copyOf(sections);
        if (sections.size() > VerticalPageLayout.SECTIONS_PER_PAGE) {
            throw new IllegalArgumentException("Too many sections in a vertical page snapshot");
        }
    }

    public static VerticalPageSnapshot fromPage(
        VerticalPagePos pos,
        long revision,
        VerticalPage<LevelChunkSection> page
    ) {
        List<SectionData> sections = new ArrayList<>();
        page.forEachOccupiedSection((absoluteSectionY, section) -> sections.add(
            new SectionData(
                VerticalPageLayout.localSectionY(absoluteSectionY),
                VerticalPageCodec.encodeSection(section))));
        return new VerticalPageSnapshot(pos.chunkX(), pos.pageY(), pos.chunkZ(), revision, sections);
    }

    public VerticalPagePos pos() {
        return new VerticalPagePos(chunkX, pageY, chunkZ);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeInt(chunkX);
        buf.writeInt(pageY);
        buf.writeInt(chunkZ);
        buf.writeVarLong(revision);
        buf.writeVarInt(sections.size());
        int total = 0;
        for (SectionData section : sections) {
            VerticalPageLayout.checkLocalSectionY(section.localSectionY());
            int length = section.payload().length;
            if (length <= 0 || length > MAX_SECTION_PAYLOAD) {
                throw new IllegalArgumentException("Invalid vertical section payload length: " + length);
            }
            total += length;
            if (total > MAX_PAGE_PAYLOAD) {
                throw new IllegalArgumentException("Vertical page payload exceeds " + MAX_PAGE_PAYLOAD + " bytes");
            }
            buf.writeByte(section.localSectionY());
            buf.writeVarInt(length);
            buf.writeBytes(section.payload());
        }
    }

    public static VerticalPageSnapshot read(FriendlyByteBuf buf) {
        int chunkX = buf.readInt();
        int pageY = buf.readInt();
        int chunkZ = buf.readInt();
        long revision = buf.readVarLong();
        int count = buf.readVarInt();
        if (count < 0 || count > VerticalPageLayout.SECTIONS_PER_PAGE) {
            throw new IllegalArgumentException("Invalid vertical page section count: " + count);
        }

        List<SectionData> sections = new ArrayList<>(count);
        boolean[] seen = new boolean[VerticalPageLayout.SECTIONS_PER_PAGE];
        int total = 0;
        for (int i = 0; i < count; i++) {
            int localY = buf.readUnsignedByte();
            VerticalPageLayout.checkLocalSectionY(localY);
            if (seen[localY]) {
                throw new IllegalArgumentException("Duplicate vertical page section " + localY);
            }
            seen[localY] = true;
            int length = buf.readVarInt();
            if (length <= 0 || length > MAX_SECTION_PAYLOAD) {
                throw new IllegalArgumentException("Invalid vertical section payload length: " + length);
            }
            total += length;
            if (total > MAX_PAGE_PAYLOAD || length > buf.readableBytes()) {
                throw new IllegalArgumentException("Truncated/oversized vertical page payload");
            }
            byte[] payload = new byte[length];
            buf.readBytes(payload);
            sections.add(new SectionData(localY, payload));
        }
        return new VerticalPageSnapshot(chunkX, pageY, chunkZ, revision, sections);
    }

    public VerticalPage<LevelChunkSection> decode(Level level) {
        VerticalPage<LevelChunkSection> page = new VerticalPage<>(pageY);
        for (SectionData section : sections) {
            page.putLocalSection(section.localSectionY(), VerticalPageCodec.decodeSection(level, section.payload()));
        }
        return page;
    }

    public record SectionData(int localSectionY, byte[] payload) {
        public SectionData {
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
}
