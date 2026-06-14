package io.github.term4.minestommechanics.util;

import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import org.jetbrains.annotations.NotNull;

/**
 * A block the entity's bounding box intersects, from a {@link BlockContact#scanShapes} pass.
 *
 * @param blockX block X coordinate
 * @param blockY block Y coordinate
 * @param blockZ block Z coordinate
 * @param block    block type at that position
 * @param face     the block face along the shallowest overlap axis (entity relative to block center)
 */
public record BlockContactHit(int blockX, int blockY, int blockZ, @NotNull Block block, @NotNull BlockFace face) {}
