package com.hjp.searchlookup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The words the cards themselves use, kept separate by the field they came from.
 *
 * Which field a word came from is the whole point. {@code searchableText()} glues name, company,
 * title, department, industry, location, memo and tags into one string, and that is exactly why a
 * company called 세종특별자치시개발원 currently answers a question about who works in
 * 세종특별자치시. A vocabulary built from that same soup would repeat the mistake, so each set here
 * is built from one kind of field and nothing else.
 *
 * <p>There are two location sets, not one, and they are not interchangeable:
 *
 * <ul>
 *   <li>{@link #administrativeLocations} — whole administrative units only, used to answer "does
 *       this place appear in the data at all". Road-name fragments must stay out: with them in, an
 *       absent region matches some fragment of some street and the search concludes the place
 *       exists.</li>
 *   <li>{@link #locationTerms} — the broader set used to recognise a place when someone names one,
 *       including the colloquial forms people actually say (판교, 강남) that carry no
 *       administrative suffix.</li>
 * </ul>
 *
 * <p>{@link #nonLocationTerms} is the guard in the other direction: a word this repository already
 * uses as somebody's name, employer, job or department is not treated as a place, however it ends.
 *
 * <p>Built from a repository snapshot and cached by {@link SearchFieldConstraintResolver}, which
 * rebuilds it when the cards change.
 */
final class SearchFieldVocabulary {

    /** Full administrative units: the evidence for "this place exists in the data". */
    final Set<String> administrativeLocations;

    /** Everything usable as a location filter, including suffix-less colloquial forms. */
    final Set<String> locationTerms;

    /** Words that appear in {@link BusinessCard#title}, and nowhere else. */
    final Set<String> titleTerms;

    /** Words this repository uses for people, employers, jobs and departments. */
    final Set<String> nonLocationTerms;

    /** One normalised {@code location + address} string per card, for existence checks. */
    private final List<String> locationHaystacks;

    private SearchFieldVocabulary(Set<String> administrativeLocations, Set<String> locationTerms,
            Set<String> titleTerms, Set<String> nonLocationTerms, List<String> locationHaystacks) {
        this.administrativeLocations = Collections.unmodifiableSet(administrativeLocations);
        this.locationTerms = Collections.unmodifiableSet(locationTerms);
        this.titleTerms = Collections.unmodifiableSet(titleTerms);
        this.nonLocationTerms = Collections.unmodifiableSet(nonLocationTerms);
        this.locationHaystacks = Collections.unmodifiableList(locationHaystacks);
    }

    static String normalize(String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Is anybody in this repository actually working at the named place? */
    boolean someoneWorksIn(String locationTerm) {
        if (locationTerm == null || locationTerm.isEmpty()) return false;
        for (String haystack : locationHaystacks) {
            if (haystack.contains(locationTerm)) return true;
        }
        return false;
    }

    static SearchFieldVocabulary from(List<BusinessCard> cards) {
        Set<String> administrative = new LinkedHashSet<>();
        Set<String> locations = new LinkedHashSet<>();
        Set<String> titles = new LinkedHashSet<>();
        Set<String> nonLocations = new LinkedHashSet<>();
        List<String> haystacks = new ArrayList<>();

        for (BusinessCard card : cards) {
            if (card == null) continue;

            String location = normalize(card.location);
            String address = normalize(card.address);
            haystacks.add((location + " " + address).trim());

            // The location field names a place outright, so it counts as administrative evidence.
            if (!location.isEmpty()) {
                administrative.add(location);
                locations.add(location);
                addColloquialForm(locations, location);
            }
            if (!address.isEmpty()) {
                String[] parts = address.split(" ");
                // Only the leading segment of an address is a whole administrative unit. The rest is
                // districts, streets and numbers, which belong in the filter vocabulary and must not
                // be allowed to vouch for a region's existence.
                if (parts.length > 0 && parts[0].length() >= 2) administrative.add(parts[0]);
                for (String part : parts) {
                    if (part.length() < 2 || isNumeric(part)) continue;
                    locations.add(part);
                    addColloquialForm(locations, part);
                }
            }

            for (String word : words(card.title)) {
                titles.add(word);
                nonLocations.add(word);
            }
            for (String word : words(card.name)) nonLocations.add(word);
            for (String word : words(card.nameEn)) nonLocations.add(word);
            for (String word : words(card.company)) nonLocations.add(word);
            for (String word : words(card.department)) nonLocations.add(word);
            for (String word : words(card.industry)) nonLocations.add(word);
        }
        // A word the cards use as a place stays a place, even if some company name repeats it.
        nonLocations.removeAll(locations);
        return new SearchFieldVocabulary(administrative, locations, titles, nonLocations, haystacks);
    }

    /**
     * Registers the everyday short form of a place: 판교역로 is where people say 판교, 강남구 is
     * where they say 강남.
     */
    private static void addColloquialForm(Set<String> locations, String term) {
        for (String suffix : SearchFieldConstraintResolver.TRIMMABLE_LOCATION_SUFFIXES) {
            if (term.length() > suffix.length() + 1 && term.endsWith(suffix)) {
                String stem = term.substring(0, term.length() - suffix.length());
                if (stem.length() >= 2) locations.add(stem);
                return;
            }
        }
    }

    private static List<String> words(String raw) {
        List<String> out = new ArrayList<>();
        String normalized = normalize(raw);
        if (normalized.isEmpty()) return out;
        for (String word : normalized.split(" ")) {
            if (word.length() >= 2 && !isNumeric(word)) out.add(word);
        }
        return out;
    }

    private static boolean isNumeric(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return !value.isEmpty();
    }
}
