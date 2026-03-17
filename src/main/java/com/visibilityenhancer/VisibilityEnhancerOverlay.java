package com.visibilityenhancer;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.HashSet;
import java.util.Set;
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

		if (local != null && config.selfOutline())
		{
			renderOutlineLayers(local, config.selfOutlineColor());
		}

		if (config.othersOutline())
		{
			Set<WorldPoint> renderedTiles = new HashSet<>();
			for (Player player : plugin.getGhostedPlayers())
			{
				WorldPoint playerPoint = player.getWorldLocation();
				if (playerPoint == null) continue;

				if (config.hideStackedOutlines())
				{
					if (localPoint != null && playerPoint.equals(localPoint)) continue;
					if (renderedTiles.contains(playerPoint)) continue;
					renderedTiles.add(playerPoint);
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