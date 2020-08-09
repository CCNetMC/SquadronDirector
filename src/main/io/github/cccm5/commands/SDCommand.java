package io.github.cccm5.commands;

import io.github.cccm5.Formation;
import io.github.cccm5.SquadronDirectorMain;
import io.github.cccm5.managers.DirectorManager;
import net.countercraft.movecraft.Rotation;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static io.github.cccm5.SquadronDirectorMain.ERROR_TAG;

public class SDCommand implements TabExecutor {
    DirectorManager manager = SquadronDirectorMain.getInstance().getDirectorManager();
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("squadrondirector"))
            return false;
        if (args.length == 0) {

            return true;
        } else if (!(sender instanceof Player)) {
            sender.sendMessage("You must be player to use subcommands");
            return true;
        }
        final Player player = (Player) sender;
        DirectorManager sqMain = SquadronDirectorMain.getInstance().getDirectorManager();
        if (args[0].equalsIgnoreCase("release")) {
            if(!player.hasPermission("Squadron.command.release")) {
                player.sendMessage(ERROR_TAG + "You do not have permissions to execute that command!");
                return true;
            }
            sqMain.releaseSquadrons(player);
        }
        if (args[0].equalsIgnoreCase("scuttle")) {
            if(!player.hasPermission("Squadron.command.scuttle")) {
                player.sendMessage(ERROR_TAG + "You do not have permissions to execute that command!");
                return true;
            }
            sqMain.scuttleSquadrons(player);
        }
        if (args[0].equalsIgnoreCase("cruise")) {
            if(!player.hasPermission("Squadron.command.cruise")) {
                player.sendMessage(ERROR_TAG + "You do not have permissions to execute that command!");
                return true;
            }
            sqMain.cruiseToggle(player);
        }
        if (args[0].equalsIgnoreCase("launch")) {
            if(!player.hasPermission("Squadron.command.launch")) {
                player.sendMessage(ERROR_TAG + "You do not have permissions to execute that command!");
                return true;
            }
            sqMain.launchModeToggle(player);
        }
        if (args[0].equalsIgnoreCase("lever")) {
            if(!player.hasPermission("Squadron.command.lever")) {
                player.sendMessage(ERROR_TAG + "You do not have permissions to execute that command!");
                return true;
            }
            sqMain.leverControl(player);
        }
        if (args[0].equalsIgnoreCase("button")) {
            if(!player.hasPermission("Squadron.command.button")) {
                player.sendMessage(ERROR_TAG + "You do not have permissions to execute that command!");
                return true;
            }
            sqMain.buttonControl(player);
        }
        if (args[0].equalsIgnoreCase("ascend")) {
            if(!player.hasPermission("Squadron.command.sdascend")) {
                player.sendMessage(ERROR_TAG + "You do not have permissions to execute that command!");
                return true;
            }
            sqMain.ascendToggle(player);
        }
        if (args[0].equalsIgnoreCase("descend")) {
            if(!player.hasPermission("Squadron.command.sddescend")) {
                player.sendMessage(ERROR_TAG + "You do not have permissions to execute that command!");
                return true;
            }
            sqMain.descendToggle(player);
        }
        if (args[0].equalsIgnoreCase("formup")) {
            if(!player.hasPermission("Squadron.command.formup")) {
                player.sendMessage(ERROR_TAG + "You do not have permissions to execute that command!");
                return true;
            }
            Formation formation = Formation.ECHELON;
            int spacing = 10;
            if (args.length == 2) {
                formation = Formation.valueOf(args[1].toUpperCase());
            }
            if (args.length == 3) {
                try {
                    spacing = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    player.sendMessage(ERROR_TAG + "Argument is not a number!");
                    return true;
                }
            }
            sqMain.toggleFormUp(player, formation, spacing);
        }
        if (args[0].equalsIgnoreCase("rotate")) {
            if(!player.hasPermission("Squadron.command.rotate")) {
                player.sendMessage(ERROR_TAG + "You do not have permissions to execute that command!");
                return true;
            }
            if (args.length == 1) {
                player.sendMessage(ERROR_TAG + "No arguments! Valid arguments are Left and Right");
                return true;
            }
            if(args[1].equalsIgnoreCase("left")) {
                manager.rotateSquadron(player, Rotation.ANTICLOCKWISE);
            } else if (args[1].equalsIgnoreCase("right")) {
                manager.rotateSquadron(player, Rotation.CLOCKWISE);
            } else {
                player.sendMessage(ERROR_TAG + "Invalid argument: " + args[1]);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> tabcompletions = Arrays.asList(
                "release",
                "scuttle",
                "cruise",
                "launch",
                "lever",
                "button",
                "ascend",
                "descend",
                "rotate",
                "formup");
        if (args.length == 0) {
            return tabcompletions;
        }
        else if (args[0].equalsIgnoreCase("formup")) {
            tabcompletions = Arrays.asList("echelon", "vic");
        }
        else if (args[0].equalsIgnoreCase("rotate")) {
            tabcompletions = Arrays.asList("left", "right");
        }
        List<String> completions = new ArrayList<>();
        for (String cmd : tabcompletions) {
            if (!cmd.startsWith(args[args.length - 1]))
                continue;
            completions.add(cmd);
        }
        return completions;
    }
}
