/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.core.trade.protocol.tasks;

import haveno.common.app.Version;
import haveno.common.taskrunner.TaskRunner;
import haveno.core.offer.Offer;
import haveno.core.trade.Trade;
import haveno.core.trade.messages.InitTradeRequest;
import haveno.core.trade.messages.TradeProtocolVersion;
import haveno.core.xmr.model.XmrAddressEntry;
import haveno.network.p2p.SendDirectMessageListener;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

import static com.google.common.base.Preconditions.checkNotNull;
import static haveno.core.util.Validator.checkTradeId;

@Slf4j
public class TakerSendInitTradeRequestToArbitrator extends TradeTask {
    @SuppressWarnings({"unused"})
    public TakerSendInitTradeRequestToArbitrator(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();

            // verify trade state
            InitTradeRequest sourceRequest = (InitTradeRequest) processModel.getTradeMessage(); // arbitrator's InitTradeRequest to taker
            checkNotNull(sourceRequest);
            checkTradeId(processModel.getOfferId(), sourceRequest);
            if (!trade.isBuyerAsTakerWithoutDeposit() && trade.getSelf().getReserveTxHash() == null) {
                throw new IllegalStateException("Taker reserve tx id is not initialized: " + trade.getSelf().getReserveTxHash());
            }

            // create request to arbitrator
            Offer offer = processModel.getOffer();
            InitTradeRequest arbitratorRequest = new InitTradeRequest(
                    TradeProtocolVersion.MULTISIG_2_3, // TODO: use processModel.getTradeProtocolVersion(), select one of maker's supported versions
                    offer.getId(),
                    trade.getAmount().longValueExact(),
                    trade.getPrice().getValue(),
                    trade.getSelf().getPaymentMethodId(),
                    trade.getMaker().getAccountId(),
                    trade.getTaker().getAccountId(),
                    trade.getMaker().getPaymentAccountId(),
                    trade.getTaker().getPaymentAccountId(),
                    trade.getTaker().getPubKeyRing(),
                    UUID.randomUUID().toString(),
                    Version.getP2PMessageVersion(),
                    null,
                    sourceRequest.getCurrentDate(),
                    trade.getMaker().getNodeAddress(),
                    trade.getTaker().getNodeAddress(),
                    trade.getArbitrator().getNodeAddress(),
                    trade.getSelf().getReserveTxHash(),
                    trade.getSelf().getReserveTxHex(),
                    trade.getSelf().getReserveTxKey(),
                    model.getXmrWalletService().getAddressEntry(offer.getId(), XmrAddressEntry.Context.TRADE_PAYOUT).get().getAddressString(),
                    trade.getChallenge());

            // send request to arbitrator
            log.info("Sending {} with offerId {} and uid {} to arbitrator {}", arbitratorRequest.getClass().getSimpleName(), arbitratorRequest.getOfferId(), arbitratorRequest.getUid(), trade.getArbitrator().getNodeAddress());
            processModel.getP2PService().sendEncryptedDirectMessage(
                    trade.getArbitrator().getNodeAddress(),
                    trade.getArbitrator().getPubKeyRing(),
                    arbitratorRequest,
                    new SendDirectMessageListener() {
                        @Override
                        public void onArrived() {
                            log.info("{} arrived at arbitrator: offerId={}", InitTradeRequest.class.getSimpleName(), trade.getId());
                            complete();
                        }
                        @Override
                        public void onFault(String errorMessage) {
                            log.warn("Failed to send {} to arbitrator, error={}.", InitTradeRequest.class.getSimpleName(), errorMessage);
                            failed();
                        }
                    });
        } catch (Throwable t) {
            failed(t);
        }
    }
}
