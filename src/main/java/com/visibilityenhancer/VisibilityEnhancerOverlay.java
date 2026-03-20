package com.visibilityenhancer;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.util.ArrayList;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.HeadIcon;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.SpriteID;
import net.runelite.api.Perspective;

import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.FontManager;

public class VisibilityEnhancerOverlay extends Overlay
{
	private final Client client;
	private final VisibilityEnhancer plugin;
	private final VisibilityEnhancerConfig config;
	private final ModelOutlineRenderer modelOutlineRenderer;

	private final SpriteManager spriteManager;

	private final Set<WorldPoint> renderedTiles = new HashSet<>();
	private final List<Player> sortedGhosts = new ArrayList<>(32);

	private int cachedOutlineWidth = -1;
	private int cachedGlowWidth = -1;
	private BasicStroke primaryStroke;
	private BasicStroke glowStroke;

	private Color cachedColor;

	private Color cachedGlowColor;
	private Color cachedFillColor;

	@Inject
	private VisibilityEnhancerOverlay(Client client, VisibilityEnhancer plugin, VisibilityEnhancerConfig config, ModelOutlineRenderer modelOutlineRenderer, SpriteManager spriteManager)
	{
		this.client = client;

		this.plugin = plugin;
		this.config = config;
		this.modelOutlineRenderer = modelOutlineRenderer;
		this.spriteManager = spriteManager;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(OverlayPriority.HIGH);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Player local = client.getLocalPlayer();
		WorldPoint localPoint = local != null ? local.getWorldLocation() : null;

		LocalPoint localLocalPoint = local != null ? local.getLocalLocation() : null;

		if (local != null && config.selfOutline())
		{
			Model localModel = local.getModel();
			if (localModel == null || localModel.getOverrideAmount() == 0)
			{
				if (config.selfUseFloorTileOutline())
				{
					renderFloorTile(graphics, local, config.selfOutlineColor());
				}
				else
				{
					renderOutlineLayers(local, config.selfOutlineColor());
				}
			}
		}

		if (config.othersOutline())
		{
			renderedTiles.clear();
			boolean hideStacked = config.hideStackedOutlines();
			boolean useFloorTile = config.othersUseFloorTileOutline();
			Color othersColor = config.othersOutlineColor();

			sortedGhosts.clear();
			sortedGhosts.addAll(plugin.getGhostedPlayers());

			if (localLocalPoint != null)
			{
				sortedGhosts.sort((p1, p2) ->
				{
					LocalPoint lp1 = p1.getLocalLocation();
					LocalPoint lp2 = p2.getLocalLocation();
					if (lp1 == null || lp2 == null) return 0;

					return Integer.compare(lp2.distanceTo(localLocalPoint), lp1.distanceTo(localLocalPoint));
				});
			}

			for (Player player : sortedGhosts)
			{
				WorldPoint playerPoint = player.getWorldLocation();
				if (playerPoint == null) continue;

				Model pModel = player.getModel();
				if (pModel != null && pModel.getOverrideAmount() != 0)
				{
					continue;
				}

				if (hideStacked)
				{
					if (localPoint != null && playerPoint.equals(localPoint)) continue;
					if (renderedTiles.contains(playerPoint)) continue;
					renderedTiles.add(playerPoint);
				}

				if (useFloorTile)
				{
					renderFloorTile(graphics, player, othersColor);
				}
				else
				{
					renderOutlineLayers(player, othersColor);
				}
			}
		}

		boolean othersCustomPrayers = config.othersTransparentPrayers();

		if (othersCustomPrayers)
		{
			Set<WorldPoint> renderedPrayerTiles = new HashSet<>();

			for (Player player : plugin.getGhostedPlayers())
			{
				WorldPoint playerPoint = player.getWorldLocation();

				if (playerPoint != null)
				{
					if (localPoint != null && playerPoint.equals(localPoint))
					{
						continue;
					}

					if (renderedPrayerTiles.contains(playerPoint))
					{
						continue;
					}

					renderedPrayerTiles.add(playerPoint);
				}

				// 1. Draw Prayer
				drawTransparentPrayer(graphics, player, config.playerOpacity());
				drawOverheadText(graphics, player);

				// 2. Draw Simplified HP Bar
				int ratio = player.getHealthRatio();
				int scale = player.getHealthScale();
				if (ratio > -1 && scale > 0)
				{
					drawTransparentHpBar(graphics, player, ratio, scale, config.playerOpacity());
				}

				// 3. Draw Simplified Hitsplats
				List<VisibilityEnhancer.CustomHitsplat> hitsplats = plugin.getCustomHitsplats().get(player);
				if (hitsplats != null && !hitsplats.isEmpty())
				{
					drawTransparentHitsplats(graphics, player, hitsplats, config.playerOpacity());
				}
			}
		}

		return null;
	}

	private void renderOutlineLayers(Player player, Color color)
	{
		if (config.enableGlow())
		{
			modelOutlineRenderer.drawOutline(player, config.glowWidth(), color, config.glowFeather());
		}
		modelOutlineRenderer.drawOutline(player, config.outlineWidth(), color, config.outlineFeather());
	}

	private void renderFloorTile(Graphics2D graphics, Player player, Color color)
	{
		Polygon poly = Perspective.getCanvasTilePoly(client, player.getLocalLocation());

		if (poly != null)
		{
			if (cachedColor == null || !cachedColor.equals(color))
			{
				cachedColor = color;
				cachedGlowColor = new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, color.getAlpha() - 100));
				cachedFillColor = new Color(color.getRed(), color.getGreen(), color.getBlue(), 50);
			}

			if (cachedOutlineWidth != config.outlineWidth())
			{
				cachedOutlineWidth = config.outlineWidth();
				primaryStroke = new BasicStroke(cachedOutlineWidth);
			}

			if (cachedGlowWidth != config.glowWidth() || cachedOutlineWidth != config.outlineWidth())
			{
				cachedGlowWidth = config.glowWidth();
				glowStroke = new BasicStroke(cachedOutlineWidth + cachedGlowWidth);
			}

			if (config.enableGlow())
			{
				graphics.setColor(cachedGlowColor);
				graphics.setStroke(glowStroke);
				graphics.draw(poly);
			}

			graphics.setColor(cachedColor);
			graphics.setStroke(primaryStroke);
			graphics.draw(poly);

			if (config.fillFloorTile())
			{
				graphics.setColor(cachedFillColor);
				graphics.fill(poly);
			}
		}
	}

	private void drawTransparentPrayer(Graphics2D graphics, Player player, int opacityPercent)
	{
		HeadIcon icon = player.getOverheadIcon();
		if (icon == null) return;

		int spriteId = getSpriteId(icon);
		if (spriteId == -1) return;

		BufferedImage prayerImage = spriteManager.getSprite(spriteId, 0);
		if (prayerImage == null) return;

		int zOffset = 20;
		Point point = player.getCanvasImageLocation(prayerImage, player.getLogicalHeight() + zOffset);
		if (point == null) return;

		int drawX = point.getX();
		int drawY = point.getY() - 25;

		float alpha = opacityPercent / 100f;
		Composite originalComposite = graphics.getComposite();
		graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

		graphics.drawImage(prayerImage, drawX, drawY, null);

		graphics.setComposite(originalComposite);
	}

	private void drawTransparentHpBar(Graphics2D graphics, Player player, int ratio, int scale, int opacityPercent)
	{
		int alpha = (int) ((opacityPercent / 100f) * 255);
		if (alpha <= 0) return;

		// Revert to using the dynamic 2D overhead calculation so it doesn't clip into the head on different camera tilts!
		// We use Z-offset of 15 to tuck it cleanly underneath the custom prayer icon.
		Point point = player.getCanvasTextLocation(graphics, "", player.getLogicalHeight() + 15);
		if (point == null) return;

		int width = 30;
		int height = 5;
		int fill = (int) (((float) ratio / scale) * width);

		int x = point.getX() - (width / 2);
		int y = point.getY();

		// Draw red background
		graphics.setColor(new Color(255, 0, 0, alpha));
		graphics.fillRect(x, y, width, height);

		// Draw green remaining health
		graphics.setColor(new Color(0, 255, 0, alpha));
		graphics.fillRect(x, y, fill, height);

		// Draw black outline
		graphics.setColor(new Color(0, 0, 0, alpha));
		graphics.drawRect(x, y, width, height);
	}

	private void drawTransparentHitsplats(Graphics2D graphics, Player player, List<VisibilityEnhancer.CustomHitsplat> hitsplats, int opacityPercent)
	{
		// Text and outline alpha (0-255)
		int alpha = (int) ((opacityPercent / 100f) * 255);
		if (alpha <= 0) return;

		// Background alpha caps at 80% of the text alpha
		int bgAlpha = (int) (alpha * 0.8f);

		LocalPoint lp = player.getLocalLocation();
		if (lp == null) return;

		Point basePoint = Perspective.localToCanvas(client, lp, client.getPlane(), player.getLogicalHeight() / 2);
		if (basePoint == null) return;

		graphics.setFont(FontManager.getRunescapeBoldFont().deriveFont(13f));
		java.awt.FontMetrics fm = graphics.getFontMetrics();

		// Shave 1 pixel off the calculated height to tighten the box vertically
		int boxTextHeight = fm.getAscent() - 1;

		// Exactly 2 pixels of padding on all sides
		int paddingX = 2;
		int paddingY = 2;

		int spacing = 18;
		int totalWidth = hitsplats.size() * spacing;

		int startX = basePoint.getX() - (totalWidth / 2) + (spacing / 2);
		int xOffset = 0;

		for (VisibilityEnhancer.CustomHitsplat hit : hitsplats)
		{
			String text = String.valueOf(hit.getAmount());
			int textWidth = fm.stringWidth(text);

			int boxWidth = textWidth + (paddingX * 2);
			int boxHeight = boxTextHeight + (paddingY * 2);

			int boxX = startX + xOffset - (boxWidth / 2);
			int boxY = basePoint.getY() - (boxHeight / 2) - 5;

			// Desaturated custom colors (softer on the eyes)
			Color backColor = hit.getAmount() == 0 ? new Color(50, 90, 160, bgAlpha) : new Color(180, 40, 40, bgAlpha);
			Color outlineColor = new Color(0, 0, 0, alpha);
			Color textColor = new Color(255, 255, 255, alpha);

			// Draw Background Box (2px rounded corners)
			graphics.setColor(backColor);
			graphics.fillRoundRect(boxX, boxY, boxWidth, boxHeight, 2, 2);

			// Draw Black Outline
			graphics.setColor(outlineColor);
			graphics.drawRoundRect(boxX, boxY, boxWidth, boxHeight, 2, 2);

			int textDrawX = boxX + paddingX;

			// +1 here actively pushes the text DOWN by 1 pixel so it stops hugging the top border!
			int textDrawY = boxY + boxTextHeight + paddingY + 1;

			// Draw Text Shadow
			graphics.setColor(outlineColor);
			graphics.drawString(text, textDrawX + 1, textDrawY + 1);

			// Draw White Text
			graphics.setColor(textColor);
			graphics.drawString(text, textDrawX, textDrawY);

			xOffset += spacing;
		}
	}

	private void drawOverheadText(Graphics2D graphics, Player player)
	{
		String text = player.getOverheadText();

		if (text == null || text.isEmpty()) return;

		int zOffset = 20;
		Point textPoint = player.getCanvasTextLocation(graphics, text, player.getLogicalHeight() + zOffset);

		if (textPoint == null) return;

		int drawX = textPoint.getX() - 1;
		int drawY = textPoint.getY() + 6;

		Point adjustedPoint = new Point(drawX, drawY);

		graphics.setFont(FontManager.getRunescapeBoldFont());
		OverlayUtil.renderTextLocation(graphics, adjustedPoint, text, Color.YELLOW);
	}

	private int getSpriteId(HeadIcon icon)
	{
		switch (icon)
		{
			case MELEE: return SpriteID.PRAYER_PROTECT_FROM_MELEE;
			case RANGED: return SpriteID.PRAYER_PROTECT_FROM_MISSILES;
			case MAGIC: return SpriteID.PRAYER_PROTECT_FROM_MAGIC;
			case RETRIBUTION: return SpriteID.PRAYER_RETRIBUTION;
			case SMITE: return SpriteID.PRAYER_SMITE;
			case REDEMPTION: return SpriteID.PRAYER_REDEMPTION;
			default: return -1;
		}
	}
}