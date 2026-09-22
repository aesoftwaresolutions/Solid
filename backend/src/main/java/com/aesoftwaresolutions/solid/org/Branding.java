package com.aesoftwaresolutions.solid.org;

/**
 * An entity's letterhead: where it is, how to reach it, how to pay it (spec 056).
 *
 * <p>Every field is optional — an entity that has filled none of it in still prints perfectly good documents.
 * {@code paymentInstructions} is for documents that ask for money; a quote must never carry it.
 */
public record Branding(String address, String phone, String email, String website, String taxId,
                       String paymentInstructions, boolean hasLogo) {

    public static final Branding EMPTY = new Branding(null, null, null, null, null, null, false);

    /** The contact details on one line, in the order a reader expects, skipping whatever is blank. */
    public String contactLine() {
        StringBuilder line = new StringBuilder();
        for (String part : new String[]{phone, email, website}) {
            if (part != null && !part.isBlank()) {
                if (line.length() > 0) {
                    line.append("   ");
                }
                line.append(part.trim());
            }
        }
        return line.toString();
    }
}
