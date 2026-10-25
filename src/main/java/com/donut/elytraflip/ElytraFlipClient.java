package com.donut.elytraflip;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ElytraFlipClient implements ClientModInitializer {
    public static double minProfit = 2_000_000, maxCapital = 500_000_000, ahTaxPercent = 0.0;
    public static int orderQuantity = 1, checkIntervalSec = 15, refreshIntervalSec = 60, retryScanSec = 10;

    // Slot ID chuẩn theo 10 ảnh GUI DonutSMP
    public static int slotYourOrdersBtn = 51;      // Rương "Your Orders" ở Orders (Page 1)
    public static int slotNewOrderBtn = 9;         // Ô "New Order" trong Your Orders
    public static int slotChooseItemBtn = 12;      // Cục đá "Item" trong New Order
    public static int slotSearchSignBtn = 50;      // Biển gỗ "Search" trong Select Item
    public static int slotSelectElytra = 0;        // Ô Elytra đầu tiên sau khi tìm "ely"
    public static int slotEnchantUnbreaking = 36;  // Sách Unbreaking III trong Pick Enchantments
    public static int slotEnchantMending = 19;     // Sách Mending trong Pick Enchantments
    public static int slotConfirmEnchant = 52;     // Kính xanh xác nhận trong Pick Enchantments
    public static int slotSetAmountBtn = 13;       // Hộp nâu số lượng trong New Order
    public static int slotSetPriceBtn = 14;        // Ngọc lục bảo "Price" trong New Order
    public static int slotConfirmNewOrder = 16;    // Kính xanh hoàn tất New Order
    public static int slotClaimItem = 13, slotCancelOrder = 15, slotAhConfirmBtn = 11;

    private static KeyBinding toggleKey;
    private boolean running = false, isRecheckingMarket = false;
    private BotState state = BotState.IDLE, nextStateAfterSign = BotState.IDLE;
    private String pendingSignText = "", statusText = "§cĐã Tắt";
    private int waitTicks = 0, monitorTicksElapsed = 0, nextCheckTickTarget = 0, soldCount = 0;
    private double lowestAhPrice = -1, highestOrderPrice = -1, myOrderPrice = 0, finalAhSellPrice = 0;
    private double lastProfit = 0, totalProfit = 0;
    private final Random random = new Random();
    private static final Pattern PRICE_PATTERN = Pattern.compile("\\$\\s*([0-9,.]+)\\s*([KMBkmb])?");

    private enum BotState {
        IDLE, SCAN_AH_OPEN, SCAN_AH_READ, SCAN_ORDER_OPEN, SCAN_ORDER_SEARCH_CLICK, SCAN_ORDER_READ,
        EVALUATE_INITIAL_PROFIT,
        ORDER_OPEN_MAIN, ORDER_CLICK_YOUR_ORDERS, ORDER_CLICK_NEW_ORDER, ORDER_CLICK_ITEM_SLOT,
        ORDER_CLICK_SEARCH_SIGN, ORDER_SELECT_ELYTRA, ORDER_ENCH_UNBREAKING, ORDER_ENCH_MENDING,
        ORDER_ENCH_CONFIRM, ORDER_CLICK_PRICE, ORDER_CHECK_AMOUNT, ORDER_FINAL_CONFIRM,
        SUBMIT_SIGN_GUI,
        MONITOR_WAIT, CHECK_MY_ORDER_OPEN, CHECK_MY_ORDER_CLICK_YOURS, CHECK_MY_ORDER_OPEN_DETAIL,
        CHECK_MY_ORDER_INSPECT, EVALUATE_15S_RECHECK,
        CANCEL_ORDER_OPEN, CANCEL_ORDER_CLICK_YOURS, CANCEL_ORDER_CLICK_ACTIVE, CANCEL_ORDER_CLICK_CANCEL,
        SELL_SCAN_AH_OPEN, SELL_SCAN_AH_READ, SELL_MOVE_ELYTRA_TO_HAND, SELL_SEND_AH_CMD, SELL_CONFIRM_AH_GUI
    }

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Bật/Tắt Auto Flip Elytra", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F6, "Donut Elytra Flip"
        ));
        registerCommands();
        HudRenderCallback.EVENT.register(this::renderHud);
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        while (toggleKey.wasPressed()) {
            if (InputUtil.isKeyPressed(client.getWindow().getHandle(), GLFW.GLFW_KEY_LEFT_SHIFT)) {
                soldCount = 0; lastProfit = 0; totalProfit = 0;
                logChat(client, "§eĐã reset bộ đếm Số Elytra đã bán & Tiền lời về 0!");
            }
            running = !running;
            if (running) {
                isRecheckingMarket = false;
                transition(BotState.SCAN_AH_OPEN, 10, 20, "§aĐang khởi động...");
                logChat(client, "§aĐã BẬT Auto Flip! Lời tối thiểu: §e" + formatMoney(minProfit));
            } else {
                state = BotState.IDLE; statusText = "§cĐã Tắt";
                logChat(client, "§cĐã TẮT! Đã bán: §b" + soldCount + " §c| Tổng lời: §a+" + formatMoney(totalProfit));
            }
        }
        if (!running || state == BotState.IDLE) return;
        if (waitTicks > 0) { waitTicks--; return; }

        switch (state) {
            case SCAN_AH_OPEN -> {
                sendCommand(client, "ah Elytra");
                transition(BotState.SCAN_AH_READ, 25, 35, "§eĐang quét giá AH...");
            }
            case SCAN_AH_READ -> {
                lowestAhPrice = (client.currentScreen instanceof HandledScreen<?> s) ? findBestElytraPrice(client, s, true) : -1;
                if (client.currentScreen instanceof HandledScreen<?>) client.player.closeHandledScreen();
                transition(BotState.SCAN_ORDER_OPEN, 15, 25, "§eĐang mở bảng Order...");
            }
            case SCAN_ORDER_OPEN -> {
                sendCommand(client, "order");
                transition(BotState.SCAN_ORDER_SEARCH_CLICK, 25, 35, "§eĐang tìm giá Order Elytra...");
            }
            case SCAN_ORDER_SEARCH_CLICK -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    double directPrice = findBestElytraPrice(client, s, false);
                    if (directPrice > 0) {
                        highestOrderPrice = directPrice;
                        client.player.closeHandledScreen();
                        transition(isRecheckingMarket ? BotState.EVALUATE_15S_RECHECK : BotState.EVALUATE_INITIAL_PROFIT, 15, 25, "§eĐang tính lời...");
                        return;
                    }
                    clickSlotIfOpen(client, slotSearchSignBtn, SlotActionType.PICKUP);
                    prepareSignSubmission("ely", BotState.SCAN_ORDER_READ);
                } else transition(BotState.SCAN_AH_OPEN, 30, 40, "§cMở lại GUI...");
            }
            case SCAN_ORDER_READ -> {
                highestOrderPrice = (client.currentScreen instanceof HandledScreen<?> s) ? findBestElytraPrice(client, s, false) : -1;
                if (client.currentScreen instanceof HandledScreen<?>) client.player.closeHandledScreen();
                transition(isRecheckingMarket ? BotState.EVALUATE_15S_RECHECK : BotState.EVALUATE_INITIAL_PROFIT, 15, 25, "§eĐang tính lời...");
            }
            case EVALUATE_INITIAL_PROFIT -> {
                if (lowestAhPrice <= 0 || highestOrderPrice <= 0) {
                    transition(BotState.SCAN_AH_OPEN, retryScanSec * 20, retryScanSec * 20 + 10, "§cĐợi quét lại...");
                    return;
                }
                double netProfit = calculateNetProfit(highestOrderPrice, lowestAhPrice);
                logChat(client, "§7Order: §e" + formatMoney(highestOrderPrice) + " §7| AH: §a" + formatMoney(lowestAhPrice) + " §7| Lời: §b+" + formatMoney(netProfit));
                if (netProfit < minProfit || highestOrderPrice * orderQuantity > maxCapital) {
                    transition(BotState.SCAN_AH_OPEN, retryScanSec * 20, retryScanSec * 20 + 10, "§eChưa đủ lời 2m...");
                    return;
                }
                myOrderPrice = highestOrderPrice;
                transition(BotState.ORDER_OPEN_MAIN, 10, 20, "§bĐặt Order: " + formatMoney(myOrderPrice));
            }
// === HET PHAN 1 - DAN TIEP PHAN 2 NGAY BEN DUOI ===
                            case ORDER_OPEN_MAIN -> {
                sendCommand(client, "order");
                transition(BotState.ORDER_CLICK_YOUR_ORDERS, 25, 35, "§bBấm Your Orders (Slot 51)...");
            }
            case ORDER_CLICK_YOUR_ORDERS -> {
                clickSlotIfOpen(client, slotYourOrdersBtn, SlotActionType.PICKUP);
                transition(BotState.ORDER_CLICK_NEW_ORDER, 20, 30, "§bBấm New Order (Slot 9)...");
            }
            case ORDER_CLICK_NEW_ORDER -> {
                int targetSlot = findSlotByName(client, "New Order", slotNewOrderBtn);
                clickSlotIfOpen(client, targetSlot, SlotActionType.PICKUP);
                transition(BotState.ORDER_CLICK_ITEM_SLOT, 20, 30, "§bBấm Choose Item (Slot 12)...");
            }
            case ORDER_CLICK_ITEM_SLOT -> {
                clickSlotIfOpen(client, slotChooseItemBtn, SlotActionType.PICKUP);
                transition(BotState.ORDER_CLICK_SEARCH_SIGN, 20, 30, "§bBấm Search (Slot 50)...");
            }
            case ORDER_CLICK_SEARCH_SIGN -> {
                clickSlotIfOpen(client, slotSearchSignBtn, SlotActionType.PICKUP);
                prepareSignSubmission("ely", BotState.ORDER_SELECT_ELYTRA);
            }
            case ORDER_SELECT_ELYTRA -> {
                clickSlotIfOpen(client, slotSelectElytra, SlotActionType.PICKUP);
                transition(BotState.ORDER_ENCH_UNBREAKING, 20, 30, "§bChọn Unbreaking III (Slot 36)...");
            }
            case ORDER_ENCH_UNBREAKING -> {
                clickSlotIfOpen(client, slotEnchantUnbreaking, SlotActionType.PICKUP);
                transition(BotState.ORDER_ENCH_MENDING, 15, 25, "§bChọn Mending (Slot 19)...");
            }
            case ORDER_ENCH_MENDING -> {
                clickSlotIfOpen(client, slotEnchantMending, SlotActionType.PICKUP);
                transition(BotState.ORDER_ENCH_CONFIRM, 15, 25, "§bXác nhận Enchant (Slot 52)...");
            }
            case ORDER_ENCH_CONFIRM -> {
                clickSlotIfOpen(client, slotConfirmEnchant, SlotActionType.PICKUP);
                transition(BotState.ORDER_CLICK_PRICE, 20, 30, "§bBấm Price (Slot 14)...");
            }
            case ORDER_CLICK_PRICE -> {
                clickSlotIfOpen(client, slotSetPriceBtn, SlotActionType.PICKUP);
                prepareSignSubmission(toCommandPrice(myOrderPrice), BotState.ORDER_CHECK_AMOUNT);
            }
            case ORDER_CHECK_AMOUNT -> {
                if (needsAmountInput(client)) {
                    clickSlotIfOpen(client, slotSetAmountBtn, SlotActionType.PICKUP);
                    prepareSignSubmission(String.valueOf(orderQuantity), BotState.ORDER_FINAL_CONFIRM);
                } else {
                    transition(BotState.ORDER_FINAL_CONFIRM, 10, 15, "§bXác nhận Order...");
                }
            }
            case ORDER_FINAL_CONFIRM -> {
                int confirmSlot = findSlotByName(client, "Confirm", slotConfirmNewOrder);
                clickSlotIfOpen(client, confirmSlot, SlotActionType.PICKUP);
                client.player.closeHandledScreen();
                monitorTicksElapsed = 0; nextCheckTickTarget = checkIntervalSec * 20;
                transition(BotState.MONITOR_WAIT, 10, 20, "§aCanh Order: " + formatMoney(myOrderPrice));
            }
            case SUBMIT_SIGN_GUI -> {
                if (client.currentScreen instanceof AbstractSignEditScreen signScreen) {
                    typeAndCloseSign(signScreen, pendingSignText);
                }
                transition(nextStateAfterSign, 20, 30, statusText);
            }
            case MONITOR_WAIT -> {
                monitorTicksElapsed++;
                int sec = monitorTicksElapsed / 20;
                statusText = "§aCanh Order " + formatMoney(myOrderPrice) + " (" + sec + "/" + refreshIntervalSec + "s)";
                if (monitorTicksElapsed >= nextCheckTickTarget) {
                    nextCheckTickTarget += checkIntervalSec * 20;
                    transition(BotState.CHECK_MY_ORDER_OPEN, 5, 10, "§bKiểm tra đơn (" + sec + "s)...");
                }
            }
            case CHECK_MY_ORDER_OPEN -> {
                sendCommand(client, "order");
                transition(BotState.CHECK_MY_ORDER_CLICK_YOURS, 25, 35, statusText);
            }
            case CHECK_MY_ORDER_CLICK_YOURS -> {
                clickSlotIfOpen(client, slotYourOrdersBtn, SlotActionType.PICKUP);
                transition(BotState.CHECK_MY_ORDER_OPEN_DETAIL, 20, 30, statusText);
            }
            case CHECK_MY_ORDER_OPEN_DETAIL -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    int mySlot = findActiveElytraOrderSlot(s);
                    if (mySlot == -1) {
                        client.player.closeHandledScreen(); isRecheckingMarket = false;
                        transition(BotState.SCAN_AH_OPEN, 20, 30, "§eĐơn trống, quét lại...");
                        return;
                    }
                    clickSlotIfOpen(client, mySlot, SlotActionType.PICKUP);
                    transition(BotState.CHECK_MY_ORDER_INSPECT, 20, 30, statusText);
                }
            }
            case CHECK_MY_ORDER_INSPECT -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    int claimSlot = findActiveElytraOrderSlot(s);
                    if (claimSlot != -1) {
                        logChat(client, "§a§lĐã có người bán Elytra! Đang rút hàng...");
                        clickSlotIfOpen(client, claimSlot, SlotActionType.QUICK_MOVE);
                        client.player.closeHandledScreen();
                        transition(BotState.SELL_SCAN_AH_OPEN, 20, 30, "§dChuẩn bị bán AH...");
                        return;
                    }
                    client.player.closeHandledScreen();
                }
                if (monitorTicksElapsed >= refreshIntervalSec * 20) {
                    isRecheckingMarket = false;
                    transition(BotState.CANCEL_ORDER_OPEN, 15, 25, "§6Làm mới Order 60s...");
                } else {
                    isRecheckingMarket = true;
                    transition(BotState.SCAN_AH_OPEN, 15, 25, "§eKiểm tra giá 15s...");
                }
            }
            case EVALUATE_15S_RECHECK -> {
                isRecheckingMarket = false;
                if (highestOrderPrice > 0 && lowestAhPrice > 0) {
                    if (highestOrderPrice <= myOrderPrice) state = BotState.MONITOR_WAIT;
                    else if (calculateNetProfit(highestOrderPrice, lowestAhPrice) >= minProfit && highestOrderPrice * orderQuantity <= maxCapital) {
                        myOrderPrice = highestOrderPrice; isRecheckingMarket = true;
                        transition(BotState.CANCEL_ORDER_OPEN, 15, 25, "§eCập nhật Order...");
                    } else state = BotState.MONITOR_WAIT;
                } else state = BotState.MONITOR_WAIT;
            }
            case CANCEL_ORDER_OPEN -> {
                sendCommand(client, "order");
                transition(BotState.CANCEL_ORDER_CLICK_YOURS, 25, 35, "§cĐang hủy Order cũ...");
            }
            case CANCEL_ORDER_CLICK_YOURS -> {
                clickSlotIfOpen(client, slotYourOrdersBtn, SlotActionType.PICKUP);
                transition(BotState.CANCEL_ORDER_CLICK_ACTIVE, 20, 30, statusText);
            }
            case CANCEL_ORDER_CLICK_ACTIVE -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    int mySlot = findActiveElytraOrderSlot(s);
                    if (mySlot != -1) clickSlotIfOpen(client, mySlot, SlotActionType.PICKUP);
                }
                transition(BotState.CANCEL_ORDER_CLICK_CANCEL, 20, 30, statusText);
            }
            case CANCEL_ORDER_CLICK_CANCEL -> {
                int cancelSlot = findSlotByName(client, "Cancel", slotCancelOrder);
                clickSlotIfOpen(client, cancelSlot, SlotActionType.PICKUP);
                client.player.closeHandledScreen();
                if (isRecheckingMarket) {
                    isRecheckingMarket = false;
                    transition(BotState.ORDER_OPEN_MAIN, 20, 30, "§bĐặt lại giá mới...");
                } else transition(BotState.SCAN_AH_OPEN, 20, 30, "§eQuét giá mới...");
            }
            case SELL_SCAN_AH_OPEN -> {
                sendCommand(client, "ah Elytra");
                transition(BotState.SELL_SCAN_AH_READ, 25, 35, "§dQuét giá AH để bán...");
            }
            case SELL_SCAN_AH_READ -> {
                double lowAh = (client.currentScreen instanceof HandledScreen<?> s) ? findBestElytraPrice(client, s, true) : -1;
                if (client.currentScreen instanceof HandledScreen<?>) client.player.closeHandledScreen();
                double minSell = calculateMinSellPrice(myOrderPrice);
                finalAhSellPrice = (lowAh > minSell) ? lowAh : minSell;
                transition(BotState.SELL_MOVE_ELYTRA_TO_HAND, 15, 25, "§dĐưa Elytra xuống tay...");
            }
            case SELL_MOVE_ELYTRA_TO_HAND -> {
                int slot = findElytraInInventory(client);
                if (slot == -1) { transition(BotState.SCAN_AH_OPEN, 30, 45, "§eQuét lại..."); return; }
                client.player.getInventory().selectedSlot = 0;
                if (slot != 0 && client.interactionManager != null) {
                    int cSlot = slot < 9 ? slot + 36 : slot;
                    client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, cSlot, 0, SlotActionType.SWAP, client.player);
                }
                transition(BotState.SELL_SEND_AH_CMD, 15, 25, "§aTreo AH: " + formatMoney(finalAhSellPrice));
            }
            case SELL_SEND_AH_CMD -> {
                if (client.player.getMainHandStack().isOf(Items.ELYTRA)) {
                    sendCommand(client, "ah sell " + toCommandPrice(finalAhSellPrice));
                    transition(BotState.SELL_CONFIRM_AH_GUI, 25, 35, statusText);
                } else transition(BotState.SELL_MOVE_ELYTRA_TO_HAND, 15, 25, statusText);
            }
            case SELL_CONFIRM_AH_GUI -> {
                if (client.currentScreen instanceof HandledScreen<?>) {
                    int confirmSlot = findSlotByName(client, "Confirm", slotAhConfirmBtn);
                    clickSlotIfOpen(client, confirmSlot, SlotActionType.PICKUP);
                    client.player.closeHandledScreen();
                }
                soldCount++;
                lastProfit = calculateNetProfit(myOrderPrice, finalAhSellPrice);
                totalProfit += lastProfit;
                logChat(client, "§a§l[ĐÃ BÁN AH] §eOrder: " + formatMoney(myOrderPrice) + " §7-> §aSell: " + formatMoney(finalAhSellPrice) + " §7| Lời: §b+" + formatMoney(lastProfit) + " §7| Đã bán: §b" + soldCount + " §7| Tổng lời: §e+" + formatMoney(totalProfit));
                isRecheckingMarket = false;
                transition(BotState.SCAN_AH_OPEN, 40, 60, "§aHoàn tất! Chuẩn bị đơn mới...");
            }
            default -> {}
        }
    }
// === HET PHAN 2 - DAN TIEP PHAN 3 NGAY BEN DUOI ===
                    private void renderHud(DrawContext ctx, RenderTickCounter tc) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) return;
        ctx.fill(6, 6, 201, 84, 0x90000000);
        ctx.fill(6, 6, 201, 8, running ? 0xFF55FF55 : 0xFFFF5555);
        double avg = soldCount > 0 ? (totalProfit / soldCount) : 0;
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§6§lAUTO FLIP ELYTRA (F6)"), 12, 12, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§7Trạng thái: " + statusText), 12, 25, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§fSố vật phẩm đã bán: §b§l" + soldCount + " §7cái"), 12, 38, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§fLời đơn gần nhất: §a+" + formatMoney(lastProfit)), 12, 51, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§fTổng tiền lời: §e§l+" + formatMoney(totalProfit) + " §7(TB: " + formatMoney(avg) + ")"), 12, 64, 0xFFFFFF);
    }

    private void prepareSignSubmission(String txt, BotState next) {
        this.pendingSignText = txt; this.nextStateAfterSign = next;
        this.state = BotState.SUBMIT_SIGN_GUI; this.waitTicks = 12 + random.nextInt(8);
    }

    // Gõ trực tiếp từng ký tự vào dòng 1 của Bảng Gỗ rồi bấm Done (Giữ nguyên các dòng dưới của server)
    private void typeAndCloseSign(AbstractSignEditScreen screen, String text) {
        for (int i = 0; i < text.length(); i++) {
            screen.charTyped(text.charAt(i), 0);
        }
        screen.close();
    }

    private boolean needsAmountInput(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> s)) return false;
        if (slotSetAmountBtn >= s.getScreenHandler().slots.size()) return false;
        ItemStack stack = s.getScreenHandler().getSlot(slotSetAmountBtn).getStack();
        if (stack.isEmpty()) return false;
        List<Text> tip = stack.getTooltip(Item.TooltipContext.DEFAULT, client.player, TooltipType.BASIC);
        for (Text line : tip) {
            String plain = line.getString().replaceAll("§[0-9a-fk-orA-FK-OR]", "");
            if (plain.contains("(0)")) return true;
        }
        return false;
    }

    private int findSlotByName(MinecraftClient client, String keyword, int fallbackSlot) {
        if (!(client.currentScreen instanceof HandledScreen<?> s)) return fallbackSlot;
        int limit = Math.min(54, s.getScreenHandler().slots.size());
        for (int i = 0; i < limit; i++) {
            ItemStack st = s.getScreenHandler().getSlot(i).getStack();
            if (!st.isEmpty() && st.getName().getString().toLowerCase().contains(keyword.toLowerCase())) {
                return i;
            }
        }
        return fallbackSlot;
    }

    private int findActiveElytraOrderSlot(HandledScreen<?> s) {
        int limit = Math.min(45, s.getScreenHandler().slots.size());
        for (int i = 0; i < limit; i++) {
            ItemStack st = s.getScreenHandler().getSlot(i).getStack();
            if (!st.isEmpty() && st.isOf(Items.ELYTRA)) return i;
        }
        return -1;
    }

    private double findBestElytraPrice(MinecraftClient client, HandledScreen<?> screen, boolean findLowest) {
        double best = -1;
        int limit = Math.min(45, screen.getScreenHandler().slots.size());
        for (int i = 0; i < limit; i++) {
            Slot slot = screen.getScreenHandler().getSlot(i);
            if (!slot.getStack().isEmpty() && slot.getStack().isOf(Items.ELYTRA)) {
                double p = extractPriceFromSlot(client, screen, i);
                if (p > 0) {
                    if (best < 0 || (findLowest ? p < best : p > best)) best = p;
                }
            }
        }
        return best;
    }

    private double extractPriceFromSlot(MinecraftClient client, HandledScreen<?> screen, int slotId) {
        if (slotId < 0 || slotId >= screen.getScreenHandler().slots.size()) return -1;
        ItemStack stack = screen.getScreenHandler().getSlot(slotId).getStack();
        if (stack.isEmpty()) return -1;
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) {
            for (Text line : lore.lines()) {
                double p = parsePriceLine(line.getString());
                if (p > 0) return p;
            }
        }
        List<Text> tip = stack.getTooltip(Item.TooltipContext.DEFAULT, client.player, TooltipType.BASIC);
        for (Text line : tip) {
            double p = parsePriceLine(line.getString());
            if (p > 0) return p;
        }
        return -1;
    }

    private double parsePriceLine(String raw) {
        Matcher m = PRICE_PATTERN.matcher(raw.replaceAll("§[0-9a-fk-orA-FK-OR]", ""));
        if (m.find()) {
            try {
                double v = Double.parseDouble(m.group(1).replace(",", ""));
                String s = m.group(2);
                if (s != null) {
                    switch (s.toUpperCase()) {
                        case "K" -> v *= 1_000;
                        case "M" -> v *= 1_000_000;
                        case "B" -> v *= 1_000_000_000;
                    }
                }
                if (v > 0) return v;
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private double calculateMinSellPrice(double orderPrice) {
        return Math.ceil((orderPrice + minProfit) / (1.0 - (ahTaxPercent / 100.0)));
    }

    private double calculateNetProfit(double orderPrice, double ahSellPrice) {
        return (ahSellPrice * (1.0 - (ahTaxPercent / 100.0))) - orderPrice;
    }

    private int findElytraInInventory(MinecraftClient client) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = client.player.getInventory().getStack(i);
            if (!s.isEmpty() && s.isOf(Items.ELYTRA)) return i;
        }
        return -1;
    }

    private void clickSlotIfOpen(MinecraftClient client, int slotId, SlotActionType type) {
        if (client.currentScreen instanceof HandledScreen<?> s && client.interactionManager != null)
            client.interactionManager.clickSlot(s.getScreenHandler().syncId, slotId, 0, type, client.player);
    }

    private void sendCommand(MinecraftClient client, String cmd) {
        if (client.player != null && client.player.networkHandler != null)
            client.player.networkHandler.sendChatCommand(cmd);
    }

    private void transition(BotState next, int min, int max, String status) {
        this.state = next;
        this.waitTicks = min + random.nextInt(Math.max(1, max - min + 1));
        this.statusText = status;
    }

    private String formatMoney(double a) {
        if (a >= 1_000_000_000) return String.format("%.2fB", a / 1_000_000_000);
        if (a >= 1_000_000) return String.format("%.2fM", a / 1_000_000);
        if (a >= 1_000) return String.format("%.1fK", a / 1_000);
        return String.valueOf(Math.round(a));
    }

    private String toCommandPrice(double a) {
        long r = Math.round(a);
        return (r % 1_000_000 == 0) ? (r / 1_000_000) + "m" : String.valueOf(r);
    }

    private void logChat(MinecraftClient client, String msg) {
        if (client.player != null) client.player.sendMessage(Text.literal("§6§l[ElytraFlip] §r" + msg), false);
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((d, r) -> d.register(ClientCommandManager.literal("elyflip")
            .then(ClientCommandManager.literal("minprofit").then(ClientCommandManager.argument("m", DoubleArgumentType.doubleArg(0.1)).executes(c -> {
                minProfit = DoubleArgumentType.getDouble(c, "m") * 1_000_000; return 1;
            })))
            .then(ClientCommandManager.literal("maxcapital").then(ClientCommandManager.argument("m", DoubleArgumentType.doubleArg(1)).executes(c -> {
                maxCapital = DoubleArgumentType.getDouble(c, "m") * 1_000_000; return 1;
            })))
            .then(ClientCommandManager.literal("checktime").then(ClientCommandManager.argument("s", IntegerArgumentType.integer(5, 300)).executes(c -> {
                checkIntervalSec = IntegerArgumentType.getInteger(c, "s"); return 1;
            })))
            .then(ClientCommandManager.literal("refreshtime").then(ClientCommandManager.argument("s", IntegerArgumentType.integer(15, 600)).executes(c -> {
                refreshIntervalSec = IntegerArgumentType.getInteger(c, "s"); return 1;
            })))
            .then(ClientCommandManager.literal("reset").executes(c -> {
                soldCount = 0; lastProfit = 0; totalProfit = 0; return 1;
            }))
        ));
    }
}
// === HOAN TAT 100% ===
            
