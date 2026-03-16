package com.visibilityenhancer;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

public class VisibilityEnhancerOverlay extends Overlay
{
	private final Client client;
	private final VisibilityEnhancer plugin;
	private final VisibilityEnhancerConfig config;
	private final ModelOutlineRenderer modelOutlineRenderer;

	@Inject
	private VisibilityEnhancerOverlay(Client client, VisibilityEnhancer plugin, VisibilityEnhancerConfig config, ModelOutlineRenderer modelOutlineRenderer)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.modelOutlineRenderer = modelOutlineRenderer;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(OverlayPriority.HIGH);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Player local = client.getLocalPlayer();
		WorldPoint localPoint = local != null ? local.getWorldLocation() : null;

		// 1. Render local player outline
		if (local != null && config.selfOutline())
		{
			renderOutlineLayers(local, config.selfOutlineColor());
		}

		// 2. Render outlines for ghosted players
		if (config.othersOutline())
		{
			// Map to count how many players are on each tile
			Map<WorldPoint, Integer> tileCounts = new HashMap<>();
			for (Player p : plugin.getGhostedPlayers())
			{
				WorldPoint wp = p.getWorldLocation();
				if (wp != null) tileCounts.put(wp, tileCounts.getOrDefault(wp, 0) + 1);
			}

			for (Player player : plugin.getGhostedPlayers())
			{
				WorldPoint playerPoint = player.getWorldLocation();
				if (playerPoint == null) continue;

				if (config.hideStackedOutlines())
				{
					// Skip if on your tile
					if (localPoint != null && playerPoint.equals(localPoint)) continue;

					// Skip if more than 1 person is on this tile (removes outline for all)
					if (tileCounts.getOrDefault(playerPoint, 0) > 1) continue;
				}

				renderOutlineLayers(player, config.othersOutlineColor());
			}
		}
		return null;
	}

	private void renderOutlineLayers(Player player, java.awt.Color color)
	{
		if (config.enableGlow())
		{
			modelOutlineRenderer.drawOutline(player, config.glowWidth(), color, config.glowFeather());
		}
		modelOutlineRenderer.drawOutline(player, config.outlineWidth(), color, config.outlineFeather());
	}
}