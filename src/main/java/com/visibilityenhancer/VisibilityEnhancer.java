package com.visibilityenhancer;

import com.google.inject.Provides;
import com.google.common.collect.ImmutableSet;
import java.util.*;
import javax.inject.Inject;
import lombok.Getter;
import lombok.AllArgsConstructor;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.*;
import net.runelite.api.kit.KitType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.Hooks;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
        name = "Raids Visibility Enhancer",
        description = "Teammate opacity, ground-view filters, and outlines for raids.",
        tags = {"raid", "opacity", "outline", "equipment"}
)
public class VisibilityEnhancer extends Plugin
{
   @Inject
   private Client client;

   @Inject
   private ClientThread clientThread;

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

   private Player cachedLocalPlayer;

   private final List<Player> inRange = new ArrayList<>();
   private final Set<Player> currentInRange = new HashSet<>();
   private final Set<Player> noLongerGhosted = new HashSet<>();

   // --- Exempt Animations for Opacity Override ---
   private static final Set<Integer> EXEMPT_ANIMATIONS = ImmutableSet.<Integer>builder()
           .add(AnimationID.CONSUMING) // Protects against CoX Overload / potion damage tints
           // Melee Specs
           .add(1378) // DWH Spec
           .add(7642).add(7643) // BGS Spec
           .add(7514) // Dragon Claws Spec
           .add(1062) // Dragon Dagger Spec
           .add(1203) // Crystal/Dragon Halberd Spec
           .add(7644).add(7640).add(7638) // AGS, SGS, ZGS Specs
           .add(10172) // Voidwaker Spec
           // Ranged Specs
           .add(5062) // Toxic Blowpipe Spec
           .add(9168) // Zaryte Crossbow Spec
           // Magic/Other Specs
           .add(8104) // Dawnbringer Spec (ToB)
           .build();

   // --- Simplified Hitsplat Tracker ---
   @Getter
   @AllArgsConstructor
   public static class CustomHitsplat
   {
      private final int amount;
      private final int despawnTick;
   }

   @Getter
   private final Map<Player, List<CustomHitsplat>> customHitsplats = new HashMap<>();
   // ----------------------------------------

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

      clientThread.invokeLater(() ->
      {
         if (client.getGameState() == GameState.LOGGED_IN)
         {
            for (Player p : client.getPlayers())
            {
               if (p != null)
               {
                  restorePlayer(p);
               }
            }
         }
      });

      ghostedPlayers.clear();
      originalEquipmentMap.clear();
      myProjectiles.clear();
      customHitsplats.clear();
      inRange.clear();
      currentInRange.clear();
      noLongerGhosted.clear();
      cachedLocalPlayer = null;
   }

   @Subscribe
   public void onHitsplatApplied(HitsplatApplied event)
   {
      if (event.getActor() instanceof Player)
      {
         Player p = (Player) event.getActor();
         if (config.othersTransparentPrayers() && ghostedPlayers.contains(p))
         {
            int amount = event.getHitsplat().getAmount();
            customHitsplats.computeIfAbsent(p, k -> new ArrayList<>())
                    .add(new CustomHitsplat(amount, client.getTickCount() + 4));
         }
      }
   }

   @Subscribe
   public void onClientTick(ClientTick event)
   {
      cachedLocalPlayer = client.getLocalPlayer();

      if (config.hideOthersProjectiles())
      {
         for (Player p : ghostedPlayers)
         {
            if (p.getGraphic() != -1)
            {
               p.setGraphic(-1);
            }

            if (p.getSpotAnims() != null)
            {
               for (ActorSpotAnim spotAnim : p.getSpotAnims())
               {
                  p.removeSpotAnim(spotAnim.getId());
               }
            }
         }
      }
   }

   @Subscribe
   public void onProjectileMoved(ProjectileMoved event)
   {
      Projectile proj = event.getProjectile();

      if (myProjectiles.contains(proj))
      {
         return;
      }

      if (client.getGameCycle() > proj.getStartCycle() + 150)
      {
         return;
      }

      Player local = client.getLocalPlayer();
      if (local == null)
      {
         return;
      }

      LocalPoint lp = local.getLocalLocation();
      if (lp == null)
      {
         return;
      }

      int dx = proj.getX1() - lp.getX();
      int dy = proj.getY1() - lp.getY();
      int distSq = (dx * dx) + (dy * dy);

      // Check if the projectile originated near us AND we are actively doing an animation
      if (distSq < (192 * 192) && local.getAnimation() != -1)
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
      customHitsplats.remove(p);
   }

   @Subscribe
   public void onPlayerChanged(PlayerChanged event)
   {
      Player p = event.getPlayer();
      Player local = client.getLocalPlayer();

      if (p == null || local == null)
      {
         return;
      }

      if (p == local)
      {
         originalEquipmentMap.remove(p);

         if (config.selfClearGround())
         {
            applyClothingFilter(p);
         }

         return;
      }

      if (ghostedPlayers.contains(p))
      {
         if (config.othersClearGround())
         {
            applyClothingFilter(p);
         }
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

      if (config.othersTransparentPrayers())
      {
         customHitsplats.values().forEach(list ->
                 list.removeIf(h -> client.getTickCount() >= h.getDespawnTick()));
      }
      else
      {
         customHitsplats.clear();
      }

      if (config.selfClearGround())
      {
         applyClothingFilter(local);
      }
      else if (originalEquipmentMap.containsKey(local))
      {
         restoreClothing(local);
      }

      LocalPoint localLoc = local.getLocalLocation();
      if (localLoc == null)
      {
         clearAllGhosting();
         return;
      }

      inRange.clear();
      currentInRange.clear();
      noLongerGhosted.clear();

      boolean ignoreFriends = config.ignoreFriends();
      int maxDist = config.proximityRange();
      int localX = localLoc.getSceneX();
      int localY = localLoc.getSceneY();

      for (Player p : client.getPlayers())
      {
         if (p == null || p == local)
         {
            continue;
         }

         boolean isFriend = ignoreFriends && (p.isFriend() || client.isFriended(p.getName(), false));

         if (isFriend)
         {
            if (ghostedPlayers.contains(p))
            {
               restorePlayer(p);
            }
            continue;
         }

         LocalPoint pLoc = p.getLocalLocation();
         if (pLoc != null)
         {
            int dist = Math.max(
                    Math.abs(localX - pLoc.getSceneX()),
                    Math.abs(localY - pLoc.getSceneY())
            );

            if (dist <= maxDist)
            {
               inRange.add(p);
            }
         }
      }

      if (config.limitAffectedPlayers() && inRange.size() > config.maxAffectedPlayers())
      {
         inRange.sort((p1, p2) ->
         {
            LocalPoint lp1 = p1.getLocalLocation();
            LocalPoint lp2 = p2.getLocalLocation();

            if (lp1 == null || lp2 == null)
            {
               return 0;
            }

            int dist1 = Math.max(
                    Math.abs(localX - lp1.getSceneX()),
                    Math.abs(localY - lp1.getSceneY())
            );
            int dist2 = Math.max(
                    Math.abs(localX - lp2.getSceneX()),
                    Math.abs(localY - lp2.getSceneY())
            );

            return Integer.compare(dist1, dist2);
         });

         currentInRange.addAll(inRange.subList(0, config.maxAffectedPlayers()));
      }
      else
      {
         currentInRange.addAll(inRange);
      }

      int opacity = config.othersClearGround() ? 100 : config.playerOpacity();
      boolean hideOthersClothes = config.othersClearGround();

      for (Player p : currentInRange)
      {
         if (opacity < 100)
         {
            applyOpacity(p, opacity);
         }
         else
         {
            restoreOpacity(p);
         }

         if (hideOthersClothes)
         {
            applyClothingFilter(p);
         }
         else if (originalEquipmentMap.containsKey(p))
         {
            restoreClothing(p);
         }
      }

      noLongerGhosted.addAll(ghostedPlayers);
      noLongerGhosted.removeAll(currentInRange);

      for (Player p : noLongerGhosted)
      {
         restorePlayer(p);
      }

      ghostedPlayers.clear();
      ghostedPlayers.addAll(currentInRange);
   }

   @Subscribe
   public void onBeforeRender(BeforeRender event)
   {
      Player local = client.getLocalPlayer();
      if (local == null)
      {
         return;
      }

      int selfOpacity = config.selfClearGround() ? 100 : config.selfOpacity();

      if (selfOpacity < 100)
      {
         applyOpacity(local, selfOpacity);
      }
      else
      {
         restoreOpacity(local);
      }

      int othersAlpha = clampAlpha(config.playerOpacity());
      int myProjAlpha = clampAlpha(config.myProjectileOpacity());

      Set<Model> forceOpaque = new HashSet<>();
      Set<Model> forceMyAlpha = new HashSet<>();
      Set<Model> forceOthersAlpha = new HashSet<>();

      for (Projectile proj : client.getProjectiles())
      {
         Model m = proj.getModel();
         if (m == null) continue;

         Actor target = proj.getInteracting();

         if (target == local || target == null)
         {
            forceOpaque.add(m);
         }
         else if (myProjectiles.contains(proj))
         {
            if (!forceOpaque.contains(m))
            {
               forceMyAlpha.add(m);
            }
         }
         else
         {
            if (!forceOpaque.contains(m) && !forceMyAlpha.contains(m))
            {
               forceOthersAlpha.add(m);
            }
         }
      }

      // --- REVERSED LOOP ORDER ---
      // Ghost opacity processes first, then your projectiles, then Solid Boss projectiles last!
      for (Model m : forceOthersAlpha)
      {
         byte[] trans = m.getFaceTransparencies();
         if (trans != null && trans.length > 0 && (trans[0] & 0xFF) != othersAlpha)
         {
            Arrays.fill(trans, (byte) othersAlpha);
         }
      }

      for (Model m : forceMyAlpha)
      {
         byte[] trans = m.getFaceTransparencies();
         if (trans != null && trans.length > 0 && (trans[0] & 0xFF) != myProjAlpha)
         {
            Arrays.fill(trans, (byte) myProjAlpha);
         }
      }

      for (Model m : forceOpaque)
      {
         byte[] trans = m.getFaceTransparencies();
         if (trans != null && trans.length > 0 && (trans[0] & 0xFF) != 0)
         {
            Arrays.fill(trans, (byte) 0);
         }
      }
   }

   private boolean shouldDraw(Renderable renderable, boolean drawingUI)
   {
      if (renderable instanceof Projectile && config.hideOthersProjectiles())
      {
         Projectile proj = (Projectile) renderable;
         Actor target = proj.getInteracting();
         return target == null || target == cachedLocalPlayer || myProjectiles.contains(proj);
      }

      if (renderable instanceof Player)
      {
         Player player = (Player) renderable;
         boolean isGhost = ghostedPlayers.contains(player);

         if (drawingUI)
         {
            if (isGhost && config.othersTransparentPrayers())
            {
               return false;
            }
         }
      }

      return true;
   }

   private int getEffectiveOpacity(Player player)
   {
      Player local = client.getLocalPlayer();
      if (player == null || local == null)
      {
         return 100;
      }

      if (player == local)
      {
         return config.selfClearGround() ? 100 : config.selfOpacity();
      }

      return config.othersClearGround() ? 100 : config.playerOpacity();
   }

   private void applyClothingFilter(Player player)
   {
      Model currentModel = player.getModel();
      if (currentModel != null && currentModel.getOverrideAmount() != 0)
      {
         restoreClothing(player);
         return;
      }

      PlayerComposition comp = player.getPlayerComposition();
      if (comp == null)
      {
         return;
      }

      int[] equipmentIds = comp.getEquipmentIds();

      if (!originalEquipmentMap.containsKey(player))
      {
         originalEquipmentMap.put(player, equipmentIds.clone());
      }

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

      if (changed)
      {
         comp.setHash();

         Model newModel = player.getModel();

         if (newModel != null)
         {
            int targetOpacity = getEffectiveOpacity(player);

            if (newModel.getOverrideAmount() != 0)
            {
               targetOpacity = 100;
            }

            if (targetOpacity < 100)
            {
               byte[] trans = newModel.getFaceTransparencies();

               if (trans != null && trans.length > 0)
               {
                  int alpha = clampAlpha(targetOpacity);

                  if ((trans[0] & 0xFF) != alpha)
                  {
                     Arrays.fill(trans, (byte) alpha);
                  }
               }
            }
            else
            {
               byte[] trans = newModel.getFaceTransparencies();
               if (trans != null && trans.length > 0 && (trans[0] & 0xFF) != 0)
               {
                  Arrays.fill(trans, (byte) 0);
               }
            }
         }
      }
   }

   private void restoreClothing(Player player)
   {
      if (!originalEquipmentMap.containsKey(player))
      {
         return;
      }

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

   private boolean isExemptAnimation(Player player)
   {
      return player != null && EXEMPT_ANIMATIONS.contains(player.getAnimation());
   }

   private void applyOpacity(Player p, int opacityPercent)
   {
      Model model = p.getModel();
      if (model == null)
      {
         return;
      }

      if (model.getOverrideAmount() != 0 && !isExemptAnimation(p))
      {
         restoreOpacity(p);
         return;
      }

      byte[] trans = model.getFaceTransparencies();
      if (trans == null || trans.length == 0)
      {
         return;
      }

      int alpha = clampAlpha(opacityPercent);
      if ((trans[0] & 0xFF) != alpha)
      {
         Arrays.fill(trans, (byte) alpha);
      }
   }

   private void restoreOpacity(Player p)
   {
      Model model = p.getModel();
      if (model == null)
      {
         return;
      }

      byte[] trans = model.getFaceTransparencies();
      if (trans != null && trans.length > 0 && (trans[0] & 0xFF) != 0)
      {
         Arrays.fill(trans, (byte) 0);
      }
   }

   private void restorePlayer(Player p)
   {
      restoreOpacity(p);
      restoreClothing(p);
   }

   private void clearAllGhosting()
   {
      for (Player p : ghostedPlayers)
      {
         restorePlayer(p);
      }

      ghostedPlayers.clear();
      originalEquipmentMap.clear();
      myProjectiles.clear();
      customHitsplats.clear();
   }

   private int clampAlpha(int opacityPercent)
   {
      if (opacityPercent >= 100)
      {
         return 0;
      }

      return (int) ((100 - opacityPercent) * 2.5);
   }

   @Provides
   VisibilityEnhancerConfig provideConfig(ConfigManager configManager)
   {
      return configManager.getConfig(VisibilityEnhancerConfig.class);
   }
}