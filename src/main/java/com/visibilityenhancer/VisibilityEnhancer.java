package com.visibilityenhancer;

import com.google.inject.Provides;
import java.util.*;
import javax.inject.Inject;
import lombok.Getter;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.kit.KitType;
import net.runelite.client.callback.Hooks;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
		name = "Visibility Enhancer",
		description = "Teammate opacity, ground-view filters, and outlines for raids.",
		tags = {"raid", "opacity", "outline", "equipment"}
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

	@Inject
	private Hooks hooks;

	@Getter
	private final Set<Player> ghostedPlayers = new HashSet<>();

	private final Map<Player, int[]> originalEquipmentMap = new HashMap<>();
	private final Set<Projectile> myProjectiles = new HashSet<>();

	private final Hooks.RenderableDrawListener drawListener = this::shouldDraw;

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		hooks.registerRenderableDrawListener(drawListener);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		hooks.unregisterRenderableDrawListener(drawListener);

		for (Player p : client.getPlayers())
		{
			if (p != null) restorePlayer(p);
		}
		ghostedPlayers.clear();
		originalEquipmentMap.clear();
		myProjectiles.clear();
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved event)
	{
		Projectile proj = event.getProjectile();

		// Only check projectiles that JUST spawned
		if (proj.getStartCycle() != client.getGameCycle()) return;

		Player local = client.getLocalPlayer();
		if (local == null) return;

		// Use getLocalLocation to find where you are on the grid
		LocalPoint lp = local.getLocalLocation();

		// We check if the projectile started within a small radius of your center.
		// OSRS tiles are 128x128 units. A 64-unit buffer ensures it catches
		// projectiles even if you're slightly offset during an animation.
		int distSq = (int) (Math.pow(proj.getX1() - lp.getX(), 2) + Math.pow(proj.getY1() - lp.getY(), 2));

		// 128*128 is one full tile radius. We'll use 150 to be safe for Shadow animations.
		boolean startsOnMe = distSq < (150 * 150);
		boolean isAttacking = local.getAnimation() != -1;

		if (startsOnMe && isAttacking)
		{
			myProjectiles.add(proj);
		}
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned event)
	{
		Player p = event.getPlayer();
		ghostedPlayers.remove(p);
		originalEquipmentMap.remove(p);
	}

	@Subscribe
	public void onPlayerChanged(PlayerChanged event)
	{
		Player p = event.getPlayer();
		if (p == null) return;

		if (p == client.getLocalPlayer())
		{
			if (config.selfClearGround()) applyClothingFilter(p);
			return;
		}

		if (ghostedPlayers.contains(p) && config.othersClearGround())
		{
			applyClothingFilter(p);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			clearAllGhosting();
			return;
		}

		myProjectiles.removeIf(p -> client.getGameCycle() >= p.getEndCycle());

		if (config.selfClearGround()) applyClothingFilter(local);
		else if (originalEquipmentMap.containsKey(local)) restoreClothing(local);

		WorldPoint localLoc = local.getWorldLocation();
		if (localLoc == null)
		{
			clearAllGhosting();
			return;
		}

		List<Player> inRange = new ArrayList<>();
		for (Player p : client.getPlayers())
		{
			if (p == null || p == local) continue;

			if (config.ignoreFriends() && (p.isFriend() || client.isFriended(p.getName(), false)))
			{
				if (ghostedPlayers.contains(p)) restorePlayer(p);
				continue;
			}

			WorldPoint pLoc = p.getWorldLocation();
			if (pLoc != null && localLoc.distanceTo(pLoc) <= config.proximityRange())
			{
				inRange.add(p);
			}
		}

		if (config.limitAffectedPlayers() && inRange.size() > config.maxAffectedPlayers())
		{
			inRange.sort(Comparator.comparingInt(p -> localLoc.distanceTo(p.getWorldLocation())));
			inRange = inRange.subList(0, config.maxAffectedPlayers());
		}

		Set<Player> currentInRange = new HashSet<>(inRange);
		int opacity = config.playerOpacity();
		boolean hideOthersClothes = config.othersClearGround();

		for (Player p : currentInRange)
		{
			if (opacity < 100) applyOpacity(p, opacity);
			else restoreOpacity(p);

			if (hideOthersClothes) applyClothingFilter(p);
			else if (originalEquipmentMap.containsKey(p)) restoreClothing(p);
		}

		Set<Player> noLongerGhosted = new HashSet<>(ghostedPlayers);
		noLongerGhosted.removeAll(currentInRange);
		for (Player p : noLongerGhosted) restorePlayer(p);

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

		int othersAlpha = clampAlpha(config.playerOpacity());
		int myProjAlpha = clampAlpha(config.myProjectileOpacity());

		for (Projectile proj : client.getProjectiles())
		{
			Actor target = proj.getInteracting();
			if (target != null && target != local)
			{
				int alpha = myProjectiles.contains(proj) ? myProjAlpha : othersAlpha;

				Model m = proj.getModel();
				if (m != null)
				{
					byte[] trans = m.getFaceTransparencies();
					if (trans != null && trans.length > 0) Arrays.fill(trans, (byte) alpha);
				}
			}
		}
	}

	private boolean shouldDraw(Renderable renderable, boolean drawingUI)
	{
		if (renderable instanceof Projectile && config.hideOthersProjectiles())
		{
			Projectile proj = (Projectile) renderable;
			Actor target = proj.getInteracting();
			return target == null || target == client.getLocalPlayer() || myProjectiles.contains(proj);
		}

		if (renderable instanceof Player && config.hideGhostExtras())
		{
			Player player = (Player) renderable;
			if (ghostedPlayers.contains(player))
			{
				if (drawingUI)
				{
					return player.getOverheadText() != null;
				}
			}
		}
		return true;
	}

	private void applyClothingFilter(Player player)
	{
		PlayerComposition comp = player.getPlayerComposition();
		if (comp == null) return;

		int[] equipmentIds = comp.getEquipmentIds();

		if (!originalEquipmentMap.containsKey(player))
		{
			originalEquipmentMap.put(player, equipmentIds.clone());
		}

		// Hides: Cape, Shield, Legs, Boots
		int[] slotsToHide = {
				KitType.CAPE.getIndex(),
				KitType.SHIELD.getIndex(),
				KitType.LEGS.getIndex(),
				KitType.BOOTS.getIndex()
		};

		boolean changed = false;
		for (int slot : slotsToHide)
		{
			if (equipmentIds[slot] != -1)
			{
				equipmentIds[slot] = -1;
				changed = true;
			}
		}
		if (changed) comp.setHash();
	}

	private void restoreClothing(Player player)
	{
		if (!originalEquipmentMap.containsKey(player)) return;

		PlayerComposition comp = player.getPlayerComposition();
		if (comp != null)
		{
			int[] original = originalEquipmentMap.get(player);
			int[] current = comp.getEquipmentIds();
			System.arraycopy(original, 0, current, 0, original.length);
			comp.setHash();
		}
		originalEquipmentMap.remove(player);
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

	private void restorePlayer(Player p)
	{
		restoreOpacity(p);
		restoreClothing(p);
	}

	private void clearAllGhosting()
	{
		for (Player p : ghostedPlayers) restorePlayer(p);
		ghostedPlayers.clear();
		originalEquipmentMap.clear();
		myProjectiles.clear();
	}

	private int clampAlpha(int opacityPercent)
	{
		if (opacityPercent >= 100) return 0;
		return (int) ((100 - opacityPercent) * 2.5);
	}

	@Provides
	VisibilityEnhancerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(VisibilityEnhancerConfig.class);
	}
}