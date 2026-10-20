package ca.twintigers.research.adapters;

import ca.twintigers.research.domain.portfolio.Account;
import ca.twintigers.research.domain.portfolio.Activity;
import ca.twintigers.research.domain.portfolio.Performance;
import ca.twintigers.research.domain.portfolio.Portfolio;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.PortfolioSource;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Test double: positions from a supplier that may fail (to simulate the source being down). */
final class FakePortfolioSource implements PortfolioSource {

    @FunctionalInterface
    interface Supplier {
        Portfolio get() throws DataSourceException;
    }

    private final Supplier supplier;

    FakePortfolioSource(Supplier supplier) {
        this.supplier = supplier;
    }

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public Set<Capability> capabilities() {
        return EnumSet.noneOf(Capability.class);
    }

    @Override
    public Portfolio portfolio() throws DataSourceException {
        return supplier.get();
    }

    @Override
    public List<Account> accounts() {
        return List.of();
    }

    @Override
    public List<Activity> activities() {
        return List.of();
    }

    @Override
    public Optional<Performance> performance(String range) {
        return Optional.empty();
    }
}
