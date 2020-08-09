package io.github.cccm5.listener;

import io.github.cccm5.SquadronDirectorMain;
import io.github.cccm5.managers.DirectorManager;
import net.countercraft.movecraft.Movecraft;
import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.Rotation;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.CraftType;
import net.countercraft.movecraft.craft.ICraft;
import net.countercraft.movecraft.events.CraftPilotEvent;
import net.countercraft.movecraft.utils.BitmapHitBox;
import net.countercraft.movecraft.utils.MathUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.Nullable;


import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.cccm5.SquadronDirectorMain.ERROR_TAG;
import static io.github.cccm5.SquadronDirectorMain.SUCCESS_TAG;

public class PlayerListener implements Listener {
    
    private final DirectorManager manager = SquadronDirectorMain.getInstance().getDirectorManager();

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent e) {
        Player player=e.getPlayer();
        if(manager.getPlayersInReconSignLocation().get(player)!=null) {
            if(player.getPotionEffect(PotionEffectType.INVISIBILITY)==null) {
                return;
            }
            if(player.getPotionEffect(PotionEffectType.INVISIBILITY).getDuration()>2479*20){ // wait a second before accepting any more move inputs
                return;
            }
            // make Movecraft not release the craft due to the player being in recon, and not on the craft
            Craft playerCraft= CraftManager.getInstance().getCraftByPlayer(player);
            if(playerCraft!=null) {
                HandlerList handlers=e.getHandlers();
                RegisteredListener[] listeners=handlers.getRegisteredListeners();
                for (RegisteredListener l : listeners) {
                    if (!l.getPlugin().isEnabled()) {
                        continue;
                    }
                    if(l.getListener() instanceof net.countercraft.movecraft.listener.PlayerListener) {
                        net.countercraft.movecraft.listener.PlayerListener pl= (net.countercraft.movecraft.listener.PlayerListener) l.getListener();
                        Class plclass= net.countercraft.movecraft.listener.PlayerListener.class;
                        try {
                            Field field = plclass.getDeclaredField("timeToReleaseAfter");
                            field.setAccessible(true);
                            final Map<Craft, Long> timeToReleaseAfter = (Map<Craft, Long>) field.get(pl);
                            if(timeToReleaseAfter.containsKey(playerCraft)) {
                                timeToReleaseAfter.put(playerCraft,System.currentTimeMillis() + 30000);
                            }
                        }
                        catch(Exception exception) {
                            exception.printStackTrace();
                        }
                    }
                }
            }
            Craft leadCraft=null;
            for(Craft c : manager.getDirectedCrafts().get(player)) {
                if ((c == null) || (c.getHitBox().isEmpty())) {
                    continue;
                }
                manager.determineCruiseDirection(c);

                if (leadCraft == null) {
                    leadCraft=c;
                    break;
                }
            }
            if(leadCraft==null) {
                return;
            }

            double dx=e.getTo().getX()-e.getFrom().getX();
            double dy=e.getTo().getY()-e.getFrom().getY();
            double dz=e.getTo().getZ()-e.getFrom().getZ();
            if(manager.getDirectedCrafts().get(player)==null || manager.getDirectedCrafts().get(player).isEmpty()) {
                return;
            }
            if(dy>0.07) {
                e.setCancelled(true);
                player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 2480 * 20, 1, false, false));
                if(manager.getPlayersStrafingUpDown().get(player)==null) {
                    manager.getPlayersStrafingUpDown().put(player,1);
                    player.sendMessage(SUCCESS_TAG+"Ascent enabled");
                    return;
                }
                if(manager.getPlayersStrafingUpDown().get(player)==2) {
                    manager.getPlayersStrafingUpDown().remove(player);
                    player.sendMessage(SUCCESS_TAG+"Descent disabled");
                    return;
                }
            }
            if(dy<-0.07) {
                e.setCancelled(true);
                player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 2480 * 20, 1, false, false));
                if(manager.getPlayersStrafingUpDown().get(player)==null) {
                    manager.getPlayersStrafingUpDown().put(player,2);
                    player.sendMessage(SUCCESS_TAG+"Descent enabled");
                    return;
                }
                if(manager.getPlayersStrafingUpDown().get(player)==1) {
                    manager.getPlayersStrafingUpDown().remove(player);
                    player.sendMessage(SUCCESS_TAG+"Ascent disabled");
                    return;
                }
            }
            // ship faces west
            if (leadCraft.getCruiseDirection() == 0x5) {
                if(dz<-0.07) {
                    e.setCancelled(true);
                    if(manager.getPlayersStrafingLeftRight().get(player)==null) {
                        manager.getPlayersStrafingLeftRight().put(player,2);
                        player.sendMessage(SUCCESS_TAG+"Strafe Right enabled");
                        return;
                    }
                    if(manager.getPlayersStrafingLeftRight().get(player)==1) {
                        manager.getPlayersStrafingLeftRight().remove(player);
                        player.sendMessage(SUCCESS_TAG+"Strafe Left disabled");
                        return;
                    }
                }
                if(dz>0.07) {
                    e.setCancelled(true);
                    if(manager.getPlayersStrafingLeftRight().get(player)==null) {
                        manager.getPlayersStrafingLeftRight().put(player,1);
                        player.sendMessage(SUCCESS_TAG+"Strafe Left enabled");
                        return;
                    }
                    if(manager.getPlayersStrafingLeftRight().get(player)==2) {
                        manager.getPlayersStrafingLeftRight().remove(player);
                        player.sendMessage(SUCCESS_TAG+"Strafe Right disabled");
                        return;
                    }
                }
            }
            // ship faces east
            if (leadCraft.getCruiseDirection() == 0x4) {
                if(dz>0.07) {
                    e.setCancelled(true);
                    if(manager.getPlayersStrafingLeftRight().get(player)==null) {
                        manager.getPlayersStrafingLeftRight().put(player,2);
                        player.sendMessage(SUCCESS_TAG+"Strafe Right enabled");
                        return;
                    }
                    if(manager.getPlayersStrafingLeftRight().get(player)==1) {
                        manager.getPlayersStrafingLeftRight().remove(player);
                        player.sendMessage(SUCCESS_TAG+"Strafe Left disabled");
                        return;
                    }
                }
                if(dz<-0.07) {
                    e.setCancelled(true);
                    if(manager.getPlayersStrafingLeftRight().get(player)==null) {
                        manager.getPlayersStrafingLeftRight().put(player,1);
                        player.sendMessage(SUCCESS_TAG+"Strafe Left enabled");
                        return;
                    }
                    if(manager.getPlayersStrafingLeftRight().get(player)==2) {
                        manager.getPlayersStrafingLeftRight().remove(player);
                        player.sendMessage(SUCCESS_TAG+"Strafe Right disabled");
                        return;
                    }
                }
            }
            // ship faces north
            if (leadCraft.getCruiseDirection() == 0x2) {
                if(dx<-0.07) {
                    e.setCancelled(true);
                    if(manager.getPlayersStrafingLeftRight().get(player)==null) {
                        manager.getPlayersStrafingLeftRight().put(player,2);
                        player.sendMessage(SUCCESS_TAG+"Strafe Right enabled");
                        return;
                    }
                    if(manager.getPlayersStrafingLeftRight().get(player)==1) {
                        manager.getPlayersStrafingLeftRight().remove(player);
                        player.sendMessage(SUCCESS_TAG+"Strafe Left disabled");
                        return;
                    }
                }
                if(dx>0.07) {
                    e.setCancelled(true);
                    if(manager.getPlayersStrafingLeftRight().get(player)==null) {
                        manager.getPlayersStrafingLeftRight().put(player,1);
                        player.sendMessage(SUCCESS_TAG+"Strafe Left enabled");
                        return;
                    }
                    if(manager.getPlayersStrafingLeftRight().get(player)==2) {
                        manager.getPlayersStrafingLeftRight().remove(player);
                        player.sendMessage(SUCCESS_TAG+"Strafe Right disabled");
                        return;
                    }
                }
            }
            // ship faces south
            if (leadCraft.getCruiseDirection() == 0x3) {
                if(dx>0.07) {
                    e.setCancelled(true);
                    if(manager.getPlayersStrafingLeftRight().get(player)==null) {
                        manager.getPlayersStrafingLeftRight().put(player,2);
                        player.sendMessage(SUCCESS_TAG+"Strafe Right enabled");
                        return;
                    }
                    if(manager.getPlayersStrafingLeftRight().get(player)==1) {
                        manager.getPlayersStrafingLeftRight().remove(player);
                        player.sendMessage(SUCCESS_TAG+"Strafe Left disabled");
                        return;
                    }
                }
                if(dx<-0.07) {
                    e.setCancelled(true);
                    if(manager.getPlayersStrafingLeftRight().get(player)==null) {
                        manager.getPlayersStrafingLeftRight().put(player,1);
                        player.sendMessage(SUCCESS_TAG+"Strafe Left enabled");
                        return;
                    }
                    if(manager.getPlayersStrafingLeftRight().get(player)==2) {
                        manager.getPlayersStrafingLeftRight().remove(player);
                        player.sendMessage(SUCCESS_TAG+"Strafe Right disabled");
                        return;
                    }
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        final Location tpLoc = manager.getPlayersInReconSignLocation().remove(event.getPlayer());
        manager.getPlayersInReconParentCrafts().remove(event.getPlayer());
        event.getPlayer().teleport(tpLoc);
    }


    @Nullable
    private final Craft craftSignIsOn(Sign sign) {
        MovecraftLocation mloc = MathUtils.bukkit2MovecraftLoc(sign.getLocation());
        CraftManager.getInstance().getCraftsInWorld(sign.getWorld());
        for (Craft craft : CraftManager.getInstance().getCraftsInWorld(sign.getWorld())) {
            if (craft == null || craft.getDisabled()) {
                continue;
            }
            if (!craft.getHitBox().contains(mloc)) {
                continue;
            }
            return craft;
        }
        return null;
    }
}
