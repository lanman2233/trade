package com.trade.quant.execution;

import com.trade.quant.core.*;
import com.trade.quant.exchange.Exchange;
import com.trade.quant.risk.RiskControl;
import com.trade.quant.risk.StopLossManager;
import com.trade.quant.strategy.Signal;
import com.trade.quant.strategy.StrategyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

/**
 * 交易引擎
 *
 * 职责：
 * 1. 连接策略引擎、风控、执行器
 * 2. 协调交易流程
 * 3. 监控持仓和止损
 */
public class TradingEngine {

    private static final Logger logger = LoggerFactory.getLogger(TradingEngine.class);

    private final StrategyEngine strategyEngine;
    private final RiskControl riskControl;
    private final OrderExecutor orderExecutor;
    private final StopLossManager stopLossManager;
    private final Exchange exchange;
    private boolean running;

    public TradingEngine(StrategyEngine strategyEngine, RiskControl riskControl,
                        OrderExecutor orderExecutor, StopLossManager stopLossManager,
                        Exchange exchange) {
        this.strategyEngine = strategyEngine;
        this.riskControl = riskControl;
        this.orderExecutor = orderExecutor;
        this.stopLossManager = stopLossManager;
        this.exchange = exchange;
        this.running = false;
    }

    /**
     * 启动交易引擎
     */
    public void start() {
        if (running) {
            logger.warn("交易引擎已在运行");
            return;
        }

        running = true;

        // 启动止损管理器
        stopLossManager.start();

        // 订阅策略信号
        strategyEngine.addSignalListener(this::onSignal);

        // 启动策略引擎
        strategyEngine.start();

        logger.info("交易引擎已启动");
    }

    /**
     * 停止交易引擎
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;

        // 停止策略引擎
        strategyEngine.stop();

        // 停止止损管理器
        stopLossManager.stop();

        logger.info("交易引擎已停止");
    }

    /**
     * 处理策略信号
     */
    private void onSignal(Signal signal) {
        if (!running) {
            return;
        }

        try {
            logger.info("收到信号: {}", signal);

            // 获取当前持仓
            List<Position> positions = exchange.getOpenPositions(signal.getSymbol());

            // 风控检查并创建订单
            Order order = riskControl.validateAndCreateOrder(signal, positions);

            if (order == null) {
                logger.info("订单被风控拒绝");
                return;
            }

            // 提交订单
            String orderId = orderExecutor.submitOrder(order);
            logger.info("订单已提交: {}", orderId);

            // 如果是入场订单，添加止损监控
            if (signal.isEntry()) {
                PositionSide side = signal.getSide() == Side.BUY ? PositionSide.LONG : PositionSide.SHORT;
                Position pos = new Position(
                        signal.getSymbol(),
                        side,
                        signal.getPrice(),
                        signal.getQuantity(),
                        signal.getStopLoss(),
                        BigDecimal.ONE
                );
                stopLossManager.monitor(pos, signal.getStopLoss());
            }

        } catch (Exception e) {
            logger.error("处理信号失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 更新持仓（定期调用）
     */
    public void updatePositions() {
        if (!running) {
            return;
        }

        try {
            // 获取所有持仓
            // TODO: 需要支持多个交易对
            List<Position> positions = List.of(); // exchange.getAllOpenPositions();

            // 更新策略引擎的持仓状态
            strategyEngine.updatePositions(positions);

            // 检查止损
            for (Position position : positions) {
                Ticker ticker = exchange.getTicker(position.getSymbol());
                List<Position> toClose = stopLossManager.checkStopLoss(ticker);

                for (Position pos : toClose) {
                    closePosition(pos, ticker.getLastPrice());
                }
            }

        } catch (Exception e) {
            logger.error("更新持仓失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 平仓
     */
    private void closePosition(Position position, BigDecimal price) {
        try {
            Side side = position.getSide() == PositionSide.LONG ? Side.SELL : Side.BUY;

            Order closeOrder = Order.builder()
                    .orderId(java.util.UUID.randomUUID().toString())
                    .symbol(position.getSymbol())
                    .side(side)
                    .type(OrderType.MARKET)
                    .quantity(position.getQuantity())
                    .price(BigDecimal.ZERO)
                    .stopLoss(BigDecimal.ZERO)
                    .takeProfit(BigDecimal.ZERO)
                    .strategyId("STOP_LOSS")
                    .build();

            orderExecutor.submitOrder(closeOrder);
            logger.warn("止损平仓: {} {} @ {}", position.getSymbol(), position.getQuantity(), price);

        } catch (Exception e) {
            logger.error("平仓失败: {}", e.getMessage(), e);
        }
    }

    public boolean isRunning() {
        return running;
    }
}
