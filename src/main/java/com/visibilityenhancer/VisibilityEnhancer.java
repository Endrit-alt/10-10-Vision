package com.visibilityenhancer;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
		name = "Visibility Enhancer",
		description = "Teammate opacity and outlines for raids.",
		tags = {"raid", "opacity", "outline"}
)
public class VisibilityEnhancer extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private VisibilityEnhancerConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private VisibilityEnhancerOverlay overlay;

	@Getter
	private final Set<Player> ghostedPlayers = new HashSet<>();

	@Override
	protected void startUp() { overlayManager.add(overlay); }

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		for (Player p : client.getPlayers()) { if (p != null) restoreOpacity(p); }
		ghostedPlayers.clear();
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned event) { ghostedPlayers.remove(event.getPlayer()); }

	@Subscribe
	public void onGameTick(GameTick event)
	{
		Player local = client.getLocalPlayer();
		if (local == null) { ghostedPlayers.clear(); return; }

		WorldPoint localLoc = local.getWorldLocation();
		if (localLoc == null) { clearAllGhosting(); return; }

		List<Player> inRange = new ArrayList<>();
		for (Player p : client.getPlayers())
		{
			if (p == null || p == local) continue;
			if (config.ignoreFriends() && (p.isFriend() || client.isFriended(p.getName(), false)))
			{
				if (ghostedPlayers.contains(p)) restoreOpacity(p);
				continue;
			}
			WorldPoint pLoc = p.getWorldLocation();
			if (pLoc != null && localLoc.distanceTo(pLoc) <= config.proximityRange()) inRange.add(p);
		}

		if (config.limitAffectedPlayers() && inRange.size() > config.maxAffectedPlayers())
		{
			inRange.sort(Comparator.comparingInt(p -> localLoc.distanceTo(p.getWorldLocation())));
			inRange = inRange.subList(0, config.maxAffectedPlayers());
		}

		Set<Player> currentInRange = new HashSet<>(inRange);
		int opacity = config.playerOpacity();

		for (Player p : currentInRange)
		{
			if (opacity < 100) applyOpacity(p, opacity);
			else restoreOpacity(p);
		}

		Set<Player> noLongerGhosted = new HashSet<>(ghostedPlayers);
		noLongerGhosted.removeAll(currentInRange);
		for (Player p : noLongerGhosted) restoreOpacity(p);

		ghostedPlayers.clear();
		ghostedPlayers.addAll(currentInRange);
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		Player local = client.getLocalPlayer();
		if (local == null) return;
		int selfOpacity = config.selfOpacity();
		if (selfOpacity < 100) applyOpacity(local, selfOpacity);
		else restoreOpacity(local);
	}

	private void applyOpacity(Player p, int opacityPercent)
	{
		Model model = p.getModel();
		if (model == null) return;
		byte[] trans = model.getFaceTransparencies();
		if (trans == null || trans.length == 0) return;
		int alpha = clampAlpha(opacityPercent);
		if ((trans[0] & 0xFF) != alpha) Arrays.fill(trans, (byte) alpha);
	}

	private void restoreOpacity(Player p)
	{
		Model model = p.getModel();
		if (model == null) return;
		byte[] trans = model.getFaceTransparencies();
		if (trans != null && trans.length > 0 && (trans[0] & 0xFF) != 0) Arrays.fill(trans, (byte) 0);
	}

	private void clearAllGhosting()
	{
		for (Player p : ghostedPlayers) restoreOpacity(p);
		ghostedPlayers.clear();
	}

	private int clampAlpha(int opacityPercent)
	{
		if (opacityPercent >= 100) return 0;
		// Map 0-100 range to 250-0 (250 is invisible but keeps outline active)
		return (int) ((100 - opacityPercent) * 2.5);
	}

	@Provides
	VisibilityEnhancerConfig provideConfig(ConfigManager configManager) { return configManager.getConfig(VisibilityEnhancerConfig.class); }
}