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
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ElytraFlipClient implements ClientModInitializer {
    public static double minProfit = 2_000_000, stopLossReduction = 1_000_000;
    public static double maxCapital = 100_000_000_000.0, ahTaxPercent = 0.0;
    public static int stopLossCompetitorCount = 3;
    public static int orderQuantity = 1, checkIntervalSec = 15, refreshIntervalSec = 60, retryScanSec = 8;
    public static int minRandomTick = 12, maxRandomTick = 20;

    public static int slotYourOrdersBtn = 51, slotNewOrderBtn = 9, slotChooseItemBtn = 12;
    public static int slotSearchSignBtn = 50, slotSelectElytra = 0, slotEnchantUnbreaking = 36;
    public static int slotEnchantMending = 19, slotConfirmEnchant = 52, slotSetAmountBtn = 13;
    public static int slotSetPriceBtn = 14, slotConfirmNewOrder = 16, slotCancelOrder = 15, slotAhConfirmBtn = 11;

    private static KeyBinding toggleKey;
    private boolean running = false, isRecheckingMarket = false;
    private BotState state = BotState.IDLE, nextStateAfterSign = BotState.IDLE;
    private String pendingSignText = "", statusText = "§cĐã Tắt";
    private int waitTicks = 0, guiWaitRetries = 0, lastReadSyncId = -1, cmdCooldownTicks = 0;
    private int monitorTicksElapsed = 0, nextCheckTickTarget = 0, soldCount = 0;
    private double lowestAhPrice = -1, highestOrderPrice = -1, myOrderPrice = 0, finalAhSellPrice = 0;
    private double lastProfit = 0, totalProfit = 0;
    private long accumulatedActiveMs = 0, lastResumeTimeMs = 0;

    private final Random random = new Random();
    private static final Pattern PRICE_PATTERN = Pattern.compile("\\$\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s*([KMBkmb])?");

    private enum BotState {
        IDLE, SCAN_AH_OPEN, SCAN_AH_READ, SCAN_ORDER_OPEN, SCAN_ORDER_READ, EVALUATE_INITIAL_PROFIT,
        ORDER_CLICK_YOUR_ORDERS, ORDER_CLICK_NEW_ORDER, ORDER_CLICK_ITEM_SLOT,
        ORDER_CLICK_SEARCH_SIGN, ORDER_SELECT_ELYTRA, ORDER_ENCH_UNBREAKING, ORDER_ENCH_MENDING,
        ORDER_ENCH_CONFIRM, ORDER_VERIFY_ELYTRA_AND_CLICK_PRICE, ORDER_CHECK_AMOUNT, ORDER_FINAL_CONFIRM,
        SUBMIT_SIGN_GUI, MONITOR_WAIT,
        CHECK_MY_ORDER_OPEN, CHECK_MY_ORDER_CLICK_YOURS, CHECK_MY_ORDER_INSPECT_LIST,
        CHECK_MY_ORDER_TAKE_ELYTRA, CHECK_MY_ORDER_VERIFY_INVENTORY, EVALUATE_15S_RECHECK,
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
        if (cmdCooldownTicks > 0) cmdCooldownTicks--;

        while (toggleKey.wasPressed()) {
            if (InputUtil.isKeyPressed(client.getWindow().getHandle(), GLFW.GLFW_KEY_LEFT_SHIFT)) {
                soldCount = 0; lastProfit = 0; totalProfit = 0;
                accumulatedActiveMs = 0; lastResumeTimeMs = System.currentTimeMillis();
                logChat(client, "§eĐã reset bộ đếm vật phẩm, tiền lời và thời gian về 0!");
            }
            running = !running;
            if (running) {
                lastResumeTimeMs = System.currentTimeMillis();
                isRecheckingMarket = false;
                transitionHuman(BotState.SCAN_AH_OPEN, "§aĐang khởi động...");
                logChat(client, "§aĐã BẬT Auto Flip! Chống Order Stone, Né Sus & Tránh Lỗ.");
            } else {
                accumulatedActiveMs += (System.currentTimeMillis() - lastResumeTimeMs);
                state = BotState.IDLE; statusText = "§cĐã Tắt";
                logChat(client, "§cĐã TẮT! Đã bán: §b" + soldCount + " §c| Tổng lời: §a+" + formatMoney(totalProfit));
            }
        }
        if (!running || state == BotState.IDLE) return;
        if (waitTicks > 0) { waitTicks--; return; }

        if (!tickScanAndCreateOrder(client)) {
            tickMonitorAndSell(client);
        }
    }
// === HẾT PHẦN 1 - DÁN TIẾP PHẦN 2 NGAY DƯỚI DÒNG NÀY ===
    private boolean tickScanAndCreateOrder(MinecraftClient client) {
        switch (state) {
            case SCAN_AH_OPEN -> {
                if (client.currentScreen != null) {
                    client.player.closeHandledScreen();
                    transition(BotState.SCAN_AH_OPEN, 10, 16, "§eĐóng bảng cũ...");
                    return true;
                }
                if (!sendCommandSafe(client, "ah Elytra")) {
                    transition(BotState.SCAN_AH_OPEN, 8, 14, "§eĐợi nhịp lệnh...");
                    return true;
                }
                lowestAhPrice = -1; guiWaitRetries = 0;
                transitionHuman(BotState.SCAN_AH_READ, "§eĐang mở AH...");
                return true;
            }
            case SCAN_AH_READ -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    int syncId = s.getScreenHandler().syncId;
                    String title = s.getTitle().getString().toLowerCase();
                    if (syncId != lastReadSyncId && !title.contains("order") && countLoadedItems(s) >= 3) {
                        lastReadSyncId = syncId;
                        lowestAhPrice = scanAhLowestPrice(client, s);
                        client.player.closeHandledScreen();
                        transitionHuman(BotState.SCAN_ORDER_OPEN, "§eQua xem /order ely...");
                        return true;
                    }
                }
                if (++guiWaitRetries < 25) transition(BotState.SCAN_AH_READ, 4, 6, "§eĐợi tải giá AH...");
                else {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    transition(BotState.SCAN_AH_OPEN, 20, 30, "§cMở lại AH...");
                }
                return true;
            }
            case SCAN_ORDER_OPEN -> {
                if (client.currentScreen != null) {
                    client.player.closeHandledScreen();
                    transition(BotState.SCAN_ORDER_OPEN, 10, 16, "§eĐợi đóng AH...");
                    return true;
                }
                if (!sendCommandSafe(client, "order ely")) {
                    transition(BotState.SCAN_ORDER_OPEN, 8, 14, "§eĐợi nhịp lệnh...");
                    return true;
                }
                highestOrderPrice = -1; guiWaitRetries = 0;
                transitionHuman(BotState.SCAN_ORDER_READ, "§eĐang mở /order ely...");
                return true;
            }
            case SCAN_ORDER_READ -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    int syncId = s.getScreenHandler().syncId;
                    String title = s.getTitle().getString().toLowerCase();
                    if (syncId != lastReadSyncId && title.contains("order") && countLoadedItems(s) >= 2) {
                        lastReadSyncId = syncId;
                        highestOrderPrice = scanOrderHighestPrice(client, s);
                        transition(isRecheckingMarket ? BotState.EVALUATE_15S_RECHECK : BotState.EVALUATE_INITIAL_PROFIT, 8, 14, "§eTính tiền lời...");
                        return true;
                    }
                }
                if (++guiWaitRetries < 25) transition(BotState.SCAN_ORDER_READ, 4, 6, "§eĐợi tải giá Order...");
                else {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    transition(BotState.SCAN_AH_OPEN, 20, 30, "§cQuét lại...");
                }
                return true;
            }
            case EVALUATE_INITIAL_PROFIT -> {
                if (lowestAhPrice <= 0 || highestOrderPrice <= 0) {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    transition(BotState.SCAN_AH_OPEN, retryScanSec * 20, retryScanSec * 20 + 20, "§cĐợi quét lại...");
                    return true;
                }
                double netProfit = calculateNetProfit(highestOrderPrice, lowestAhPrice);
                logChat(client, "§7Order: §e" + formatMoney(highestOrderPrice) + " §7| AH: §a" + formatMoney(lowestAhPrice) + " §7| Lời: §b+" + formatMoney(netProfit));
                if (Math.round(netProfit) < Math.round(minProfit) || highestOrderPrice * orderQuantity > maxCapital) {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    transition(BotState.SCAN_AH_OPEN, retryScanSec * 20, retryScanSec * 20 + 20, "§eChưa đủ lời (Đợi " + retryScanSec + "s)");
                    return true;
                }
                myOrderPrice = highestOrderPrice; guiWaitRetries = 0;
                transitionHuman(BotState.ORDER_CLICK_YOUR_ORDERS, "§bĐủ lời! Bấm Your Orders...");
                return true;
            }
            case ORDER_CLICK_YOUR_ORDERS -> {
                if (!(client.currentScreen instanceof HandledScreen<?>)) {
                    if (sendCommandSafe(client, "order ely")) transitionHuman(BotState.ORDER_CLICK_YOUR_ORDERS, "§bMở lại trang Order...");
                    else transition(BotState.ORDER_CLICK_YOUR_ORDERS, 10, 15, "§bĐợi mở Order...");
                    return true;
                }
                clickSlotIfOpen(client, findSlotByName(client, "Your Orders", slotYourOrdersBtn), SlotActionType.PICKUP);
                guiWaitRetries = 0;
                transitionHuman(BotState.ORDER_CLICK_NEW_ORDER, "§bBấm New Order...");
                return true;
            }
            case ORDER_CLICK_NEW_ORDER -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("your orders")) {
                    clickSlotIfOpen(client, findSlotByName(client, "New Order", slotNewOrderBtn), SlotActionType.PICKUP);
                    guiWaitRetries = 0;
                    transitionHuman(BotState.ORDER_CLICK_ITEM_SLOT, "§bBấm Choose Item...");
                    return true;
                }
                if (++guiWaitRetries < 15) transition(BotState.ORDER_CLICK_NEW_ORDER, 4, 6, "§bĐợi bảng Your Orders...");
                else transitionHuman(BotState.ORDER_CLICK_YOUR_ORDERS, "§cThử lại Your Orders...");
                return true;
            }
            case ORDER_CLICK_ITEM_SLOT -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("new order")) {
                    clickSlotIfOpen(client, slotChooseItemBtn, SlotActionType.PICKUP);
                    guiWaitRetries = 0;
                    transitionHuman(BotState.ORDER_CLICK_SEARCH_SIGN, "§bBấm Search...");
                    return true;
                }
                if (++guiWaitRetries < 15) transition(BotState.ORDER_CLICK_ITEM_SLOT, 4, 6, "§bĐợi bảng New Order...");
                else transitionHuman(BotState.ORDER_CLICK_NEW_ORDER, "§cThử lại New Order...");
                return true;
            }
            case ORDER_CLICK_SEARCH_SIGN -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("select item")) {
                    clickSlotIfOpen(client, findSlotByName(client, "Search", slotSearchSignBtn), SlotActionType.PICKUP);
                    prepareSignSubmission("ely", BotState.ORDER_SELECT_ELYTRA);
                    return true;
                }
                if (++guiWaitRetries < 15) transition(BotState.ORDER_CLICK_SEARCH_SIGN, 4, 6, "§bĐợi bảng Select Item...");
                else transitionHuman(BotState.ORDER_CLICK_ITEM_SLOT, "§cBấm lại Choose Item...");
                return true;
            }
            case ORDER_SELECT_ELYTRA -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("select item")) {
                    int elySlot = findActiveElytraOrderSlot(s);
                    if (elySlot != -1) {
                        clickSlotIfOpen(client, elySlot, SlotActionType.PICKUP);
                        guiWaitRetries = 0;
                        transitionHuman(BotState.ORDER_ENCH_UNBREAKING, "§bChọn Unbreaking III...");
                        return true;
                    }
                }
                if (++guiWaitRetries < 20) transition(BotState.ORDER_SELECT_ELYTRA, 4, 6, "§bĐợi hiện Elytra...");
                else transitionHuman(BotState.ORDER_CLICK_SEARCH_SIGN, "§cTìm lại chữ ely...");
                return true;
            }
            case ORDER_ENCH_UNBREAKING -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("enchant")) {
                    clickSlotIfOpen(client, slotEnchantUnbreaking, SlotActionType.PICKUP);
                    transitionHuman(BotState.ORDER_ENCH_MENDING, "§bChọn Mending...");
                    return true;
                }
                if (++guiWaitRetries < 15) transition(BotState.ORDER_ENCH_UNBREAKING, 4, 6, "§bĐợi bảng Enchant...");
                else transitionHuman(BotState.ORDER_SELECT_ELYTRA, "§cChọn lại Elytra...");
                return true;
            }
            case ORDER_ENCH_MENDING -> {
                clickSlotIfOpen(client, slotEnchantMending, SlotActionType.PICKUP);
                transitionHuman(BotState.ORDER_ENCH_CONFIRM, "§bLưu Enchant...");
                return true;
            }
            case ORDER_ENCH_CONFIRM -> {
                clickSlotIfOpen(client, slotConfirmEnchant, SlotActionType.PICKUP);
                guiWaitRetries = 0;
                transitionHuman(BotState.ORDER_VERIFY_ELYTRA_AND_CLICK_PRICE, "§bKiểm tra Elytra & Nhập giá...");
                return true;
            }
            case ORDER_VERIFY_ELYTRA_AND_CLICK_PRICE -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("new order")) {
                    ItemStack chosen = s.getScreenHandler().getSlot(slotChooseItemBtn).getStack();
                    if (!chosen.isEmpty() && chosen.isOf(Items.ELYTRA)) {
                        clickSlotIfOpen(client, slotSetPriceBtn, SlotActionType.PICKUP);
                        prepareSignSubmission(toCommandPrice(myOrderPrice), BotState.ORDER_CHECK_AMOUNT);
                        return true;
                    } else if (++guiWaitRetries >= 10) {
                        logChat(client, "§c[Chống Lỗi] Ô vật phẩm vẫn là Stone, đang chọn lại Elytra!");
                        guiWaitRetries = 0;
                        transitionHuman(BotState.ORDER_CLICK_ITEM_SLOT, "§cChọn lại Elytra...");
                        return true;
                    }
                }
                if (++guiWaitRetries < 18) transition(BotState.ORDER_VERIFY_ELYTRA_AND_CLICK_PRICE, 4, 6, "§bĐợi xác nhận Elytra...");
                else transitionHuman(BotState.ORDER_CLICK_ITEM_SLOT, "§cChọn lại Elytra...");
                return true;
            }
            case ORDER_CHECK_AMOUNT -> {
                if (needsAmountInput(client)) {
                    clickSlotIfOpen(client, slotSetAmountBtn, SlotActionType.PICKUP);
                    prepareSignSubmission(String.valueOf(orderQuantity), BotState.ORDER_FINAL_CONFIRM);
                } else transitionHuman(BotState.ORDER_FINAL_CONFIRM, "§bXác nhận tạo Order...");
                return true;
            }
            case ORDER_FINAL_CONFIRM -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    ItemStack chosen = s.getScreenHandler().getSlot(slotChooseItemBtn).getStack();
                    if (chosen.isEmpty() || !chosen.isOf(Items.ELYTRA)) {
                        logChat(client, "§c[Chặn Khẩn Cấp] Phát hiện không phải Elytra! Đã hủy đặt đơn!");
                        client.player.closeHandledScreen();
                        transition(BotState.SCAN_AH_OPEN, 30, 45, "§cĐã chặn đặt nhầm Stone!");
                        return true;
                    }
                    clickSlotIfOpen(client, findSlotByName(client, "Confirm", slotConfirmNewOrder), SlotActionType.PICKUP);
                    client.player.closeHandledScreen();
                }
                monitorTicksElapsed = 0; nextCheckTickTarget = checkIntervalSec * 20;
                transitionHuman(BotState.MONITOR_WAIT, "§aCanh Order: " + formatMoney(myOrderPrice));
                return true;
            }
            default -> { return false; }
        }
    }
// === HẾT PHẦN 2 - DÁN TIẾP PHẦN 3 NGAY DƯỚI DÒNG NÀY ===
               private void tickMonitorAndSell(MinecraftClient client) {
        switch (state) {
            case SUBMIT_SIGN_GUI -> {
                if (client.currentScreen instanceof AbstractSignEditScreen signScreen) {
                    fillAndCloseSign(signScreen, pendingSignText);
                    guiWaitRetries = 0;
                    transitionHuman(nextStateAfterSign, statusText);
                } else if (++guiWaitRetries < 20) {
                    transition(BotState.SUBMIT_SIGN_GUI, 3, 5, statusText);
                } else transitionHuman(nextStateAfterSign, statusText);
            }
            case MONITOR_WAIT -> {
                if (findElytraInInventory(client) != -1) {
                    transitionHuman(BotState.SELL_SCAN_AH_OPEN, "§aCó Elytra! Đem bán AH...");
                    return;
                }
                monitorTicksElapsed++;
                if (client.currentScreen == null && monitorTicksElapsed % 140 == 0 && random.nextBoolean()) {
                    client.player.setYaw(client.player.getYaw() + (random.nextFloat() - 0.5f) * 1.2f);
                    client.player.setPitch(Math.clamp(client.player.getPitch() + (random.nextFloat() - 0.5f) * 0.6f, -85.0f, 85.0f));
                }
                int sec = monitorTicksElapsed / 20;
                statusText = "§aCanh Order " + formatMoney(myOrderPrice) + " (" + sec + "/" + refreshIntervalSec + "s)";
                if (monitorTicksElapsed >= nextCheckTickTarget) {
                    nextCheckTickTarget += checkIntervalSec * 20;
                    transitionHuman(BotState.CHECK_MY_ORDER_OPEN, "§bKiểm tra Your Orders...");
                }
            }
            case CHECK_MY_ORDER_OPEN -> {
                if (!sendCommandSafe(client, "order ely")) {
                    transition(BotState.CHECK_MY_ORDER_OPEN, 8, 12, statusText);
                    return;
                }
                transitionHuman(BotState.CHECK_MY_ORDER_CLICK_YOURS, "§bMở Your Orders...");
            }
            case CHECK_MY_ORDER_CLICK_YOURS -> {
                clickSlotIfOpen(client, findSlotByName(client, "Your Orders", slotYourOrdersBtn), SlotActionType.PICKUP);
                guiWaitRetries = 0;
                transitionHuman(BotState.CHECK_MY_ORDER_INSPECT_LIST, "§bKiểm tra đơn Elytra...");
            }
            case CHECK_MY_ORDER_INSPECT_LIST -> {
                if (client.currentScreen instanceof HandledScreen<?> s && s.getTitle().getString().toLowerCase().contains("your orders")) {
                    int myElytraSlot = findActiveElytraOrderSlot(s);
                    if (myElytraSlot == -1) {
                        client.player.closeHandledScreen();
                        if (findElytraInInventory(client) != -1) transitionHuman(BotState.SELL_SCAN_AH_OPEN, "§aĐem bán Elytra lên AH...");
                        else { isRecheckingMarket = false; transitionHuman(BotState.SCAN_AH_OPEN, "§eĐơn trống, tạo đơn mới..."); }
                        return;
                    }
                    if (isOrderDeliveredInList(client, s.getScreenHandler().getSlot(myElytraSlot).getStack())) {
                        logChat(client, "§a§lĐơn Order Elytra đã hoàn thành! Đang lấy Elytra ra...");
                        clickSlotIfOpen(client, myElytraSlot, SlotActionType.PICKUP);
                        transitionHuman(BotState.CHECK_MY_ORDER_TAKE_ELYTRA, "§aĐang rút Elytra về túi...");
                        return;
                    }
                    client.player.closeHandledScreen();
                    if (monitorTicksElapsed >= refreshIntervalSec * 20) {
                        isRecheckingMarket = false;
                        transitionHuman(BotState.CANCEL_ORDER_OPEN, "§6Làm mới Order 60s...");
                    } else {
                        isRecheckingMarket = true;
                        transitionHuman(BotState.SCAN_AH_OPEN, "§eKiểm tra giá 15s...");
                    }
                    return;
                }
                if (++guiWaitRetries < 15) transition(BotState.CHECK_MY_ORDER_INSPECT_LIST, 4, 6, statusText);
                else transitionHuman(BotState.CHECK_MY_ORDER_OPEN, statusText);
            }
            case CHECK_MY_ORDER_TAKE_ELYTRA -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    int claimBtn = findSlotByName(client, "Claim", -1);
                    if (claimBtn == -1) claimBtn = findSlotByName(client, "Collect", -1);
                    if (claimBtn != -1) clickSlotIfOpen(client, claimBtn, SlotActionType.PICKUP);

                    int limit = Math.min(45, s.getScreenHandler().slots.size());
                    for (int i = 0; i < limit; i++) {
                        ItemStack st = s.getScreenHandler().getSlot(i).getStack();
                        if (!st.isEmpty() && st.isOf(Items.ELYTRA)) {
                            clickSlotIfOpen(client, i, SlotActionType.QUICK_MOVE);
                            clickSlotIfOpen(client, i, SlotActionType.PICKUP);
                        }
                    }
                }
                transitionHuman(BotState.CHECK_MY_ORDER_VERIFY_INVENTORY, "§bKiểm tra túi đồ...");
            }
            case CHECK_MY_ORDER_VERIFY_INVENTORY -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    int limit = Math.min(45, s.getScreenHandler().slots.size());
                    for (int i = 0; i < limit; i++) {
                        if (s.getScreenHandler().getSlot(i).getStack().isOf(Items.ELYTRA))
                            clickSlotIfOpen(client, i, SlotActionType.QUICK_MOVE);
                    }
                    client.player.closeHandledScreen();
                }
                if (findElytraInInventory(client) != -1) {
                    logChat(client, "§a§lĐã lấy Elytra thành công! Chuyển sang bán trên AH...");
                    transitionHuman(BotState.SELL_SCAN_AH_OPEN, "§dChuẩn bị bán lên AH...");
                } else transitionHuman(BotState.MONITOR_WAIT, "§aTiếp tục canh Order...");
            }
            case EVALUATE_15S_RECHECK -> {
                isRecheckingMarket = false;
                if (highestOrderPrice > 0 && lowestAhPrice > 0) {
                    long newProfit = Math.round(calculateNetProfit(highestOrderPrice, lowestAhPrice));
                    if (highestOrderPrice <= myOrderPrice) {
                        if (client.currentScreen != null) client.player.closeHandledScreen();
                        state = BotState.MONITOR_WAIT;
                    } else if (newProfit >= Math.round(minProfit) && highestOrderPrice * orderQuantity <= maxCapital) {
                        myOrderPrice = highestOrderPrice; isRecheckingMarket = true;
                        transitionHuman(BotState.CANCEL_ORDER_CLICK_YOURS, "§eCập nhật Order mới...");
                    } else {
                        if (client.currentScreen != null) client.player.closeHandledScreen();
                        state = BotState.MONITOR_WAIT;
                    }
                } else {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    state = BotState.MONITOR_WAIT;
                }
            }
            case CANCEL_ORDER_OPEN -> {
                if (!sendCommandSafe(client, "order ely")) {
                    transition(BotState.CANCEL_ORDER_OPEN, 8, 12, "§cĐợi nhịp lệnh...");
                    return;
                }
                transitionHuman(BotState.CANCEL_ORDER_CLICK_YOURS, "§cĐang hủy Order cũ...");
            }
            case CANCEL_ORDER_CLICK_YOURS -> {
                clickSlotIfOpen(client, findSlotByName(client, "Your Orders", slotYourOrdersBtn), SlotActionType.PICKUP);
                transitionHuman(BotState.CANCEL_ORDER_CLICK_ACTIVE, statusText);
            }
            case CANCEL_ORDER_CLICK_ACTIVE -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    int mySlot = findActiveElytraOrderSlot(s);
                    if (mySlot != -1) clickSlotIfOpen(client, mySlot, SlotActionType.PICKUP);
                }
                transitionHuman(BotState.CANCEL_ORDER_CLICK_CANCEL, statusText);
            }
            case CANCEL_ORDER_CLICK_CANCEL -> {
                int cancelSlot = findSlotByName(client, "Cancel", slotCancelOrder);
                if (cancelSlot == slotCancelOrder) cancelSlot = findSlotByName(client, "Delete", slotCancelOrder);
                clickSlotIfOpen(client, cancelSlot, SlotActionType.PICKUP);
                client.player.closeHandledScreen();
                if (findElytraInInventory(client) != -1) transitionHuman(BotState.SELL_SCAN_AH_OPEN, "§dCó Elytra! Đem bán AH...");
                else if (isRecheckingMarket) {
                    isRecheckingMarket = false;
                    transitionHuman(BotState.ORDER_CLICK_YOUR_ORDERS, "§bĐặt lại giá mới...");
                } else transitionHuman(BotState.SCAN_AH_OPEN, "§eQuét giá mới...");
            }
            case SELL_SCAN_AH_OPEN -> {
                if (client.currentScreen != null) {
                    client.player.closeHandledScreen();
                    transition(BotState.SELL_SCAN_AH_OPEN, 10, 16, "§dĐóng bảng cũ...");
                    return;
                }
                if (!sendCommandSafe(client, "ah Elytra")) {
                    transition(BotState.SELL_SCAN_AH_OPEN, 8, 14, "§dĐợi nhịp lệnh...");
                    return;
                }
                guiWaitRetries = 0;
                transitionHuman(BotState.SELL_SCAN_AH_READ, "§dQuét giá AH để bán...");
            }
            case SELL_SCAN_AH_READ -> {
                if (client.currentScreen instanceof HandledScreen<?> s) {
                    int syncId = s.getScreenHandler().syncId;
                    String title = s.getTitle().getString().toLowerCase();
                    if (syncId != lastReadSyncId && !title.contains("order") && countLoadedItems(s) >= 3) {
                        lastReadSyncId = syncId;
                        finalAhSellPrice = determineSmartSellPrice(client, s, myOrderPrice);
                        client.player.closeHandledScreen();
                        transitionHuman(BotState.SELL_MOVE_ELYTRA_TO_HAND, "§dĐưa Elytra xuống tay...");
                        return;
                    }
                }
                if (++guiWaitRetries < 25) transition(BotState.SELL_SCAN_AH_READ, 4, 6, "§dĐang tải giá AH...");
                else {
                    if (client.currentScreen != null) client.player.closeHandledScreen();
                    finalAhSellPrice = calculateCustomSellPrice(myOrderPrice, minProfit);
                    transitionHuman(BotState.SELL_MOVE_ELYTRA_TO_HAND, "§dĐưa Elytra xuống tay...");
                }
            }
            case SELL_MOVE_ELYTRA_TO_HAND -> {
                int slot = findElytraInInventory(client);
                if (slot == -1) { transitionHuman(BotState.SCAN_AH_OPEN, "§eQuét lại..."); return; }
                client.player.getInventory().selectedSlot = 0;
                if (slot != 0 && client.interactionManager != null) {
                    int cSlot = slot < 9 ? slot + 36 : slot;
                    client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, cSlot, 0, SlotActionType.SWAP, client.player);
                }
                transitionHuman(BotState.SELL_SEND_AH_CMD, "§aTreo AH: " + formatMoney(finalAhSellPrice));
            }
            case SELL_SEND_AH_CMD -> {
                if (client.player.getMainHandStack().isOf(Items.ELYTRA)) {
                    if (!sendCommandSafe(client, "ah sell " + toCommandPrice(finalAhSellPrice))) {
                        transition(BotState.SELL_SEND_AH_CMD, 8, 12, statusText);
                        return;
                    }
                    transitionHuman(BotState.SELL_CONFIRM_AH_GUI, statusText);
                } else transitionHuman(BotState.SELL_MOVE_ELYTRA_TO_HAND, statusText);
            }
            case SELL_CONFIRM_AH_GUI -> {
                if (client.currentScreen instanceof HandledScreen<?>) {
                    clickSlotIfOpen(client, findSlotByName(client, "Confirm", slotAhConfirmBtn), SlotActionType.PICKUP);
                    client.player.closeHandledScreen();
                }
                soldCount++;
                lastProfit = calculateNetProfit(myOrderPrice, finalAhSellPrice);
                totalProfit += lastProfit;
                logChat(client, "§a§l[ĐÃ BÁN AH] §eOrder: " + formatMoney(myOrderPrice) + " §7-> §aSell: " + formatMoney(finalAhSellPrice) + " §7| Lời: §b+" + formatMoney(lastProfit) + " §7| Tổng: §e+" + formatMoney(totalProfit) + " §7| Lời/1h: §d+" + formatMoney(getProfitPerHour()) + "/h");
                isRecheckingMarket = false;
                transition(BotState.SCAN_AH_OPEN, 30, 45, "§aHoàn tất! Chuẩn bị đơn mới...");
            }
            default -> {}
        }
    }
// === HẾT PHẦN 3 - DÁN TIẾP PHẦN 4 NGAY DƯỚI DÒNG NÀY ===
              private void renderHud(DrawContext ctx, RenderTickCounter tc) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) return;
        ctx.fill(6, 6, 220, 96, 0x90000000);
        ctx.fill(6, 6, 220, 8, running ? 0xFF55FF55 : 0xFFFF5555);
        double avg = soldCount > 0 ? (totalProfit / soldCount) : 0;
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§6§lAUTO FLIP ELYTRA (F6)"), 12, 12, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§7Trạng thái: " + statusText), 12, 24, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§fĐã bán: §b§l" + soldCount + " cái §7| Gần nhất: §a+" + formatMoney(lastProfit)), 12, 36, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§fTổng lời: §e§l+" + formatMoney(totalProfit) + " §7(TB: " + formatMoney(avg) + ")"), 12, 48, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§fTiền lời / 1h: §d§l+" + formatMoney(getProfitPerHour()) + "/h"), 12, 60, 0xFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§7TG chạy: §f" + getActiveTimeFormatted() + " §7| Tick: " + minRandomTick + "-" + maxRandomTick), 12, 72, 0xFFFFFF);
    }

    private double determineSmartSellPrice(MinecraftClient client, HandledScreen<?> screen, double orderPrice) {
        double targetMinSell = calculateCustomSellPrice(orderPrice, minProfit);
        double reducedProfit = Math.max(500_000, minProfit - stopLossReduction);
        double safeFloorPrice = calculateCustomSellPrice(orderPrice, reducedProfit);

        double lowAh = -1;
        int competitorsBelowTarget = 0;
        int limit = Math.min(45, screen.getScreenHandler().slots.size());

        for (int i = 0; i < limit; i++) {
            ItemStack st = screen.getScreenHandler().getSlot(i).getStack();
            if (!st.isEmpty() && st.isOf(Items.ELYTRA) && hasExactOrderEnchants(client, st)) {
                double p = extractUnitPrice(client, st);
                if (p > 0) {
                    if (lowAh < 0 || p < lowAh) lowAh = p;
                    if (p < targetMinSell) competitorsBelowTarget++;
                }
            }
        }
        if (lowAh >= targetMinSell) return lowAh;
        if (lowAh > 0 && competitorsBelowTarget >= stopLossCompetitorCount) {
            double stopLossSell = Math.max(lowAh, safeFloorPrice);
            logChat(client, "§e[Tránh Lỗ] Có " + competitorsBelowTarget + " Elytra giá " + formatMoney(lowAh) + " trên AH! Giảm 1M bán giá §a" + formatMoney(stopLossSell));
            return stopLossSell;
        }
        return targetMinSell;
    }

    private boolean isOrderDeliveredInList(MinecraftClient client, ItemStack stack) {
        Item.TooltipContext ctx = client.world != null ? Item.TooltipContext.create(client.world) : Item.TooltipContext.DEFAULT;
        for (Text line : stack.getTooltip(ctx, client.player, TooltipType.BASIC)) {
            String plain = line.getString().replaceAll("§[0-9a-fk-orA-FK-OR]", "").toLowerCase();
            if (plain.contains("order completed") || plain.contains("1/1 delivered")) return true;
        }
        return false;
    }

    private int countLoadedItems(HandledScreen<?> screen) {
        int count = 0, limit = Math.min(54, screen.getScreenHandler().slots.size());
        for (int i = 0; i < limit; i++) {
            if (!screen.getScreenHandler().getSlot(i).getStack().isEmpty()) count++;
        }
        return count;
    }

    private double getProfitPerHour() {
        long activeMs = accumulatedActiveMs + (running ? (System.currentTimeMillis() - lastResumeTimeMs) : 0);
        if (activeMs < 5000 || totalProfit <= 0) return 0;
        return totalProfit / (activeMs / 3_600_000.0);
    }

    private String getActiveTimeFormatted() {
        long totalSec = (accumulatedActiveMs + (running ? (System.currentTimeMillis() - lastResumeTimeMs) : 0)) / 1000;
        return String.format("%02d:%02d:%02d", totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60);
    }

    private void prepareSignSubmission(String txt, BotState next) {
        this.pendingSignText = txt; this.nextStateAfterSign = next;
        this.guiWaitRetries = 0; this.state = BotState.SUBMIT_SIGN_GUI;
        this.waitTicks = minRandomTick + random.nextInt(Math.max(1, maxRandomTick - minRandomTick + 1));
    }

    private void fillAndCloseSign(AbstractSignEditScreen signScreen, String line1) {
        try {
            for (int i = 0; i < line1.length(); i++) signScreen.charTyped(line1.charAt(i), 0);
            for (Field f : AbstractSignEditScreen.class.getDeclaredFields()) {
                if (f.getType() == String[].class) {
                    f.setAccessible(true);
                    String[] messages = (String[]) f.get(signScreen);
                    if (messages != null && messages.length > 0) messages[0] = line1;
                    break;
                }
            }
            signScreen.close();
        } catch (Exception e) { signScreen.close(); }
    }

    private boolean hasExactOrderEnchants(MinecraftClient client, ItemStack stack) {
        boolean hasUnb3 = false, hasMend = false;
        ItemEnchantmentsComponent enchComp = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (enchComp != null) {
            for (RegistryEntry<Enchantment> entry : enchComp.getEnchantments()) {
                String id = entry.getIdAsString().toLowerCase();
                int lvl = enchComp.getLevel(entry);
                if (id.contains("curse") || id.contains("vanishing") || id.contains("binding")) return false;
                if (id.contains("unbreaking")) {
                    if (lvl == 3) hasUnb3 = true;
                    else return false;
                }
                if (id.contains("mending") && lvl >= 1) hasMend = true;
            }
        }
        Item.TooltipContext ctx = client.world != null ? Item.TooltipContext.create(client.world) : Item.TooltipContext.DEFAULT;
        for (Text line : stack.getTooltip(ctx, client.player, TooltipType.BASIC)) {
            String plain = line.getString().replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim().toLowerCase();
            if (plain.contains("curse") || plain.contains("vanishing") || plain.contains("binding")) return false;
            if (plain.matches(".*unbreaking\\s+(iii|3)\\b.*") || plain.contains("chậm hỏng iii")) hasUnb3 = true;
            else if (plain.matches(".*unbreaking\\s+(i|ii|1|2)\\b.*")) return false;
            if (plain.contains("mending") || plain.contains("sửa chữa")) hasMend = true;
        }
        return hasUnb3 && hasMend;
    }

    private double scanAhLowestPrice(MinecraftClient client, HandledScreen<?> screen) {
        double lowestEnchanted = -1;
        int limit = Math.min(45, screen.getScreenHandler().slots.size());
        for (int i = 0; i < limit; i++) {
            ItemStack st = screen.getScreenHandler().getSlot(i).getStack();
            if (!st.isEmpty() && st.isOf(Items.ELYTRA) && hasExactOrderEnchants(client, st)) {
                double p = extractUnitPrice(client, st);
                if (p > 0 && (lowestEnchanted < 0 || p < lowestEnchanted)) lowestEnchanted = p;
            }
        }
        return lowestEnchanted;
    }

    private double scanOrderHighestPrice(MinecraftClient client, HandledScreen<?> screen) {
        double highest = -1;
        int limit = Math.min(45, screen.getScreenHandler().slots.size());
        for (int i = 0; i < limit; i++) {
            ItemStack st = screen.getScreenHandler().getSlot(i).getStack();
            if (!st.isEmpty() && st.isOf(Items.ELYTRA)) {
                double p = extractUnitPrice(client, st);
                if (p > highest) highest = p;
            }
        }
        return highest;
    }

    private double extractUnitPrice(MinecraftClient client, ItemStack stack) {
        Item.TooltipContext ctx = client.world != null ? Item.TooltipContext.create(client.world) : Item.TooltipContext.DEFAULT;
        List<Text> lines = stack.getTooltip(ctx, client.player, TooltipType.BASIC);
        double fallback = -1;
        for (Text line : lines) {
            String plain = line.getString().replaceAll("§[0-9a-fk-orA-FK-OR]", "").toLowerCase();
            if (plain.contains("paid") || plain.contains("total") || plain.contains("seller") || plain.contains("expire")) continue;
            double p = parsePriceLine(plain);
            if (p > 0) {
                if (plain.contains("price") || plain.contains("each") || plain.contains("/item") || plain.contains("giá")) return p;
                if (fallback < 0) fallback = p;
            }
        }
        return fallback;
    }

    private double parsePriceLine(String raw) {
        Matcher m = PRICE_PATTERN.matcher(raw);
        double bestOnLine = -1;
        while (m.find()) {
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
                if (v > bestOnLine) bestOnLine = Math.round(v);
            } catch (NumberFormatException ignored) {}
        }
        return bestOnLine;
    }

    private boolean needsAmountInput(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> s) || slotSetAmountBtn >= s.getScreenHandler().slots.size()) return false;
        ItemStack stack = s.getScreenHandler().getSlot(slotSetAmountBtn).getStack();
        if (stack.isEmpty()) return false;
        Item.TooltipContext ctx = client.world != null ? Item.TooltipContext.create(client.world) : Item.TooltipContext.DEFAULT;
        for (Text line : stack.getTooltip(ctx, client.player, TooltipType.BASIC)) {
            if (line.getString().replaceAll("§[0-9a-fk-orA-FK-OR]", "").contains("(0)")) return true;
        }
        return false;
    }

    private int findSlotByName(MinecraftClient client, String keyword, int fallbackSlot) {
        if (!(client.currentScreen instanceof HandledScreen<?> s)) return fallbackSlot;
        int limit = Math.min(54, s.getScreenHandler().slots.size());
        for (int i = 0; i < limit; i++) {
            ItemStack st = s.getScreenHandler().getSlot(i).getStack();
            if (!st.isEmpty() && st.getName().getString().toLowerCase().contains(keyword.toLowerCase())) return i;
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

    private double calculateCustomSellPrice(double orderPrice, double targetProfit) {
        return Math.ceil((orderPrice + targetProfit) / (1.0 - (ahTaxPercent / 100.0)));
    }

    private double calculateNetProfit(double orderPrice, double ahSellPrice) {
        return Math.round((ahSellPrice * (1.0 - (ahTaxPercent / 100.0))) - orderPrice);
    }

    private int findElytraInInventory(MinecraftClient client) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = client.player.getInventory().getStack(i);
            if (!s.isEmpty() && s.isOf(Items.ELYTRA)) return i;
        }
        return -1;
    }

    private void clickSlotIfOpen(MinecraftClient client, int slotId, SlotActionType type) {
        if (slotId >= 0 && client.currentScreen instanceof HandledScreen<?> s && client.interactionManager != null)
            client.interactionManager.clickSlot(s.getScreenHandler().syncId, slotId, 0, type, client.player);
    }

    private boolean sendCommandSafe(MinecraftClient client, String cmd) {
        if (cmdCooldownTicks > 0) return false;
        if (client.player != null && client.player.networkHandler != null) {
            client.player.networkHandler.sendChatCommand(cmd);
            cmdCooldownTicks = 32 + random.nextInt(12);
            return true;
        }
        return false;
    }

    private void transitionHuman(BotState next, String status) {
        int extraJitter = (random.nextInt(5) == 0) ? random.nextInt(8) : 0;
        transition(next, minRandomTick + extraJitter, maxRandomTick + extraJitter, status);
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
            .then(ClientCommandManager.literal("randomtick")
                .then(ClientCommandManager.argument("min", IntegerArgumentType.integer(5, 100))
                    .then(ClientCommandManager.argument("max", IntegerArgumentType.integer(8, 200)).executes(c -> {
                        minRandomTick = IntegerArgumentType.getInteger(c, "min");
                        maxRandomTick = Math.max(minRandomTick + 2, IntegerArgumentType.getInteger(c, "max"));
                        return 1;
                    }))))
            .then(ClientCommandManager.literal("reset").executes(c -> {
                soldCount = 0; lastProfit = 0; totalProfit = 0;
                accumulatedActiveMs = 0; lastResumeTimeMs = System.currentTimeMillis();
                return 1;
            }))
        ));
    }
}
// === HOÀN TẤT 100% ===
                                                          
