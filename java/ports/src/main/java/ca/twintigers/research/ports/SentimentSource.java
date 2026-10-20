package ca.twintigers.research.ports;

import ca.twintigers.research.domain.market.Sentiment;

import java.util.List;

/** Market sentiment indices for the Markets page (stocks first, then others such as crypto). */
public interface SentimentSource {

    /** The indices that could be read; one failing does not hide the others. */
    List<Sentiment> sentiment() throws DataSourceException;
}
