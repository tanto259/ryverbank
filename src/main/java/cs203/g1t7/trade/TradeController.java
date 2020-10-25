package cs203.g1t7.trade;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Iterator;
import javax.validation.Valid;

import java.text.DateFormat;
import java.text.SimpleDateFormat;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;

import cs203.g1t7.transaction.*;
import cs203.g1t7.account.*;
import cs203.g1t7.users.*;
import cs203.g1t7.asset.*;

import org.springframework.dao.EmptyResultDataAccessException;

@RestController
public class TradeController {
    private TradeRepository trade;
    private TransactionRepository transactions;
    private AccountRepository accounts;
    private AssetRepository portfolio;
    private AccountService accountService;
    private QuoteController quote;

    public TradeController(AssetRepository portfolio, TransactionRepository transactions, AccountRepository accounts, TradeRepository trade){
        this.transactions = transactions;
        this.accounts = accounts;
        this.portfolio = portfolio;
        this.trade = trade;
    }

    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/trades")
    public Trade addTrade(@PathVariable Integer account_id, @Valid @RequestBody Trade newTrade) {
        Account buyer = accounts.findById(account_id).get();
        
        if (buyer == null) throw new AccountNotFoundException(account_id);

        int quantity = newTrade.getQuantity();
        
        if (quantity % 100 != 0) throw new InvalidTradeException("Buy or sell have to be in multiples of 100");

        String action = newTrade.getAction();

        if (!action.equals("buy") || !action.equals("sell")) {
            throw new InvalidTradeException("Invalid action parameter");
        }
        
        updateTrade();
        processTrade(newTrade);
        return newTrade;
    }

    @GetMapping("/api/trades/{t_id}")
    public Trade getTrade(@PathVariable (value = "t_id") Integer t_id) {
        User user = (User)SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        Integer userId = user.getId();

        if(userId == null) throw new TradeForbiddenException();

        if(!accounts.existsById(userId)) {
            throw new AccountNotFoundException(userId);
        }
        return trade.findByIdAndAccountId(t_id, userId).get();
    }

    @DeleteMapping("/api/trades/{id}")
    public void deleteTrade(@PathVariable Integer id){
        User user = (User)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        try{
            Trade temp = trade.findById(id).get();
            if(user.getId() != temp.getAccount().getId()) {
                throw new TradeForbiddenException(id);
            }
            temp.setStatus("cancelled");
         }catch(EmptyResultDataAccessException e) {
            throw new TradeNotFoundException(id);
         }
    }

    public void processTrade(Trade newTrade) {
        DateFormat df = new SimpleDateFormat("HH");
        Date dateobj = new Date();
        String sb = df.format(dateobj).toString();

        int hour = Integer.parseInt(sb);
        if (hour < 9 || hour >= 17) {
            newTrade.setStatus("expired");
            return;
        }

        double price;
        double cost;
        boolean marketTrade = false;
        int quantity = newTrade.getQuantity();
        int account_id = newTrade.getAccount().getId();
        Quote tempQuote = quote.getQuote(newTrade.getSymbol());
        Integer buyerId = newTrade.getBuyer();
        Account buyer = accountService.getAccount(buyerId);
        
        List<Asset> buyerPortfolio = portfolio.findByCustomerId(account_id);
        Iterator<Asset> portfolioIter = buyerPortfolio.iterator();

        String action = newTrade.getAction();

        if (action.equals("buy")) {
            price = newTrade.getBid();
            if (price == 0) {
                price = tempQuote.getBid();
                marketTrade = true;
            }
            cost = price * quantity;
            if (cost > buyer.getBalance()) throw new InsufficientFundsException();
            if (marketTrade) {
                if (tempQuote.getBid_volume() >= newTrade.getQuantity()) {
                    newTrade.setStatus("filled");
                    newTrade.setFilled_quantity(newTrade.getQuantity());
                    tempQuote.setBid_volume(tempQuote.getBid_volume() - newTrade.getQuantity());
                } else {
                    newTrade.setStatus("partial-filled");
                    newTrade.setFilled_quantity(tempQuote.getBid_volume());
                    tempQuote.setBid_volume(0);
                }
                Transaction newTransactions = new Transaction(buyerId, -1, cost);
                updateBalanceSender(buyerId, newTransactions);
            } else {
                newTrade.setStatus("open");
            }
        } else {
            price = newTrade.getAsk();
            if (price == 0) {
                price = tempQuote.getAsk();
                marketTrade = true;
            }
            cost = price * quantity;
            boolean portFound = false;
            while (portfolioIter.hasNext()) {
                Asset temp = portfolioIter.next();
                if (temp.getSymbol() == newTrade.getSymbol()) {
                    if (temp.getAmount() > newTrade.getQuantity()) throw new InvalidTradeException("Amount of assets not enough");
                    portFound = true;
                    break;
                }
            }
            if (!portFound) throw new InvalidTradeException("Assets not found on portfolio");
            if (marketTrade) {
                if (tempQuote.getAsk_volume() >= newTrade.getQuantity()) {
                    newTrade.setStatus("filled");
                    newTrade.setFilled_quantity(newTrade.getQuantity());
                    tempQuote.setAsk_volume(tempQuote.getAsk_volume() - newTrade.getQuantity());
                } else {
                    newTrade.setStatus("partial-filled");
                    newTrade.setFilled_quantity(tempQuote.getAsk_volume());
                    tempQuote.setAsk_volume(0);
                }
                newTrade.setStatus("filled");
                Transaction newTransactions = new Transaction(-1, buyerId, cost);
                updateBalanceReceiver(buyerId, newTransactions);
            } else {
                newTrade.setStatus("open");
            }
        }

        trade.save(newTrade);
    }

    public void updateTrade() {
        List<Trade> list = trade.findByStatusOrStatus("open", "partial-filled");
        Iterator listIter = list.iterator();
        while (listIter.hasNext()) {
            Trade tempTrade = (Trade) listIter.next();
            processTrade(tempTrade);
        }
    }

    public Transaction updateBalanceSender(Integer id_from, Transaction t) {
        if (id_from == -1) return transactions.save(t);        
        
        Account from = accounts.findById(id_from).get();
        double amount = t.getAmount();

        return accounts.findById(id_from).map(account ->{
            account.setAvailable_balance(from.getAvailable_balance() - amount);
            account.setBalance(from.getBalance() - amount);
            t.setAccount(account);
            return transactions.save(t);
        }).orElseThrow(() -> new AccountNotFoundException(id_from));
    }

    public Transaction updateBalanceReceiver(Integer id_to, Transaction t) {
        if (id_to == -1) return transactions.save(t);
        
        Account to = accounts.findById(id_to).get();
        double amount = t.getAmount();

        return accounts.findById(id_to).map(account ->{
            account.setAvailable_balance(to.getAvailable_balance() + amount);
            account.setBalance(to.getBalance() + amount);
            t.setAccount(account);
            return transactions.save(t);
        }).orElseThrow(() -> new AccountNotFoundException(id_to));
    }
}