package com.aesoftwaresolutions.solid.org;

import com.aesoftwaresolutions.solid.common.ApiProblemException;
import com.aesoftwaresolutions.solid.common.NotFoundException;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and writes the letterhead. Public API of the org module for anything that draws a document. */
@Service
public class BrandingService {

    /** A logo big enough to print and small enough that nobody notices it in a backup. */
    public static final int MAX_LOGO_BYTES = 1024 * 1024;

    /** The logo as stored: bytes and the type its own first bytes say it is. */
    public record Logo(byte[] bytes, String contentType) {
    }

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;

    BrandingService(JdbcClient db, OrgScope orgScope, OrgService orgs) {
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
    }

    @Transactional(readOnly = true)
    public Branding get(UUID orgId, UUID entityId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> db.sql("""
                        select address, phone, email, website, tax_id, payment_instructions,
                               logo_bytes is not null as has_logo
                        from org.branding where entity_id = ?""")
                .param(entityId)
                .query((rs, n) -> new Branding(rs.getString("address"), rs.getString("phone"),
                        rs.getString("email"), rs.getString("website"), rs.getString("tax_id"),
                        rs.getString("payment_instructions"), rs.getBoolean("has_logo")))
                .optional()
                .orElse(Branding.EMPTY));
    }

    /** Saves the whole letterhead; a null field clears it. */
    @Transactional
    public Branding save(UUID orgId, UUID entityId, String address, String phone, String email, String website,
                         String taxId, String paymentInstructions) {
        orgs.getEntity(orgId, entityId);
        orgScope.run(orgId, () -> db.sql("""
                        insert into org.branding (entity_id, org_id, address, phone, email, website, tax_id,
                                                  payment_instructions)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (entity_id) do update set address = excluded.address, phone = excluded.phone,
                            email = excluded.email, website = excluded.website, tax_id = excluded.tax_id,
                            payment_instructions = excluded.payment_instructions, updated_at = now()""")
                .params(entityId, orgId, trimmed(address), trimmed(phone), trimmed(email), trimmed(website),
                        trimmed(taxId), trimmed(paymentInstructions))
                .update());
        return get(orgId, entityId);
    }

    /**
     * Stores the logo, after deciding from the file's own first bytes what it really is. A script named
     * {@code logo.png} is refused here, not when someone opens the PDF.
     */
    @Transactional
    public Branding saveLogo(UUID orgId, UUID entityId, byte[] bytes) {
        orgs.getEntity(orgId, entityId);
        if (bytes.length > MAX_LOGO_BYTES) {
            throw new ApiProblemException(400, "LOGO_TOO_LARGE", "A logo must be 1 MB or smaller");
        }
        String contentType = imageType(bytes)
                .orElseThrow(() -> new ApiProblemException(400, "UNSUPPORTED_LOGO",
                        "A logo must be a PNG or a JPEG"));
        requireSaneDimensions(bytes);
        orgScope.run(orgId, () -> db.sql("""
                        insert into org.branding (entity_id, org_id, logo_bytes, logo_content_type)
                        values (?, ?, ?, ?)
                        on conflict (entity_id) do update set logo_bytes = excluded.logo_bytes,
                            logo_content_type = excluded.logo_content_type, updated_at = now()""")
                .params(entityId, orgId, bytes, contentType)
                .update());
        return get(orgId, entityId);
    }

    /** Plenty for a letterhead printed a few centimetres wide; far short of a decompression bomb. */
    private static final int MAX_LOGO_SIDE = 4000;

    /**
     * Reads the width and height from the image header without decoding a single pixel. A megabyte of PNG can
     * describe a picture that takes hundreds of megabytes to decode, and every invoice, quote and statement
     * decodes the logo afresh — so the byte limit alone does not bound the memory (spec 065, row 16).
     */
    private static void requireSaneDimensions(byte[] bytes) {
        try (javax.imageio.stream.ImageInputStream in =
                     javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
            java.util.Iterator<javax.imageio.ImageReader> readers = javax.imageio.ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new ApiProblemException(400, "UNSUPPORTED_LOGO", "That image could not be read");
            }
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width > MAX_LOGO_SIDE || height > MAX_LOGO_SIDE) {
                    throw new ApiProblemException(400, "LOGO_TOO_LARGE", "A logo must be at most "
                            + MAX_LOGO_SIDE + " pixels on each side; this one is " + width + "×" + height);
                }
            } finally {
                reader.dispose();
            }
        } catch (java.io.IOException e) {
            throw new ApiProblemException(400, "UNSUPPORTED_LOGO", "That image could not be read");
        }
    }

    @Transactional
    public void deleteLogo(UUID orgId, UUID entityId) {
        orgs.getEntity(orgId, entityId);
        orgScope.run(orgId, () -> db.sql("""
                        update org.branding set logo_bytes = null, logo_content_type = null, updated_at = now()
                        where entity_id = ?""")
                .param(entityId)
                .update());
    }

    @Transactional(readOnly = true)
    public Optional<Logo> logo(UUID orgId, UUID entityId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> db.sql("""
                        select logo_bytes, logo_content_type from org.branding
                        where entity_id = ? and logo_bytes is not null""")
                .param(entityId)
                .query((rs, n) -> new Logo(rs.getBytes("logo_bytes"), rs.getString("logo_content_type")))
                .optional());
    }

    /** The logo of an entity that has one, or nothing — for the documents, which must cope with either. */
    public Optional<Logo> logoOrNone(UUID orgId, UUID entityId) {
        try {
            return logo(orgId, entityId);
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }

    /** PNG and JPEG only, decided by the first bytes rather than by what the file is called. */
    private static Optional<String> imageType(byte[] bytes) {
        if (bytes.length > 4 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E
                && bytes[3] == 0x47) {
            return Optional.of("image/png");
        }
        if (bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return Optional.of("image/jpeg");
        }
        return Optional.empty();
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
