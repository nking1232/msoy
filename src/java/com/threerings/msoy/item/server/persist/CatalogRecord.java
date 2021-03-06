//
// $Id$

package com.threerings.msoy.item.server.persist;

import java.sql.Timestamp;
import java.util.Date;

import com.google.common.base.Function;

import com.samskivert.util.StringUtil;

import com.samskivert.depot.PersistentRecord;
import com.samskivert.depot.annotation.Entity;
import com.samskivert.depot.annotation.GeneratedValue;
import com.samskivert.depot.annotation.GenerationType;
import com.samskivert.depot.annotation.Id;
import com.samskivert.depot.annotation.Index;
import com.samskivert.depot.annotation.Transient;
import com.samskivert.depot.expression.ColumnExp;

import com.threerings.io.Streamable;

import com.threerings.msoy.data.all.GroupName;
import com.threerings.msoy.data.all.MemberName;
import com.threerings.msoy.item.gwt.CatalogListing;
import com.threerings.msoy.item.gwt.ItemDetail;
import com.threerings.msoy.item.gwt.ListingCard;
import com.threerings.msoy.money.data.all.Currency;

/**
 * Represents a catalog listing of an item.
 */
@Entity
public abstract class CatalogRecord extends PersistentRecord
    implements Streamable
{
    // AUTO-GENERATED: FIELDS START
    public static final Class<CatalogRecord> _R = CatalogRecord.class;
    public static final ColumnExp<Integer> CATALOG_ID = colexp(_R, "catalogId");
    public static final ColumnExp<Integer> LISTED_ITEM_ID = colexp(_R, "listedItemId");
    public static final ColumnExp<Integer> ORIGINAL_ITEM_ID = colexp(_R, "originalItemId");
    public static final ColumnExp<Timestamp> LISTED_DATE = colexp(_R, "listedDate");
    public static final ColumnExp<Currency> CURRENCY = colexp(_R, "currency");
    public static final ColumnExp<Integer> COST = colexp(_R, "cost");
    public static final ColumnExp<Integer> PRICING = colexp(_R, "pricing");
    public static final ColumnExp<Integer> SALES_TARGET = colexp(_R, "salesTarget");
    public static final ColumnExp<Integer> PURCHASES = colexp(_R, "purchases");
    public static final ColumnExp<Integer> RETURNS = colexp(_R, "returns");
    public static final ColumnExp<Integer> FAVORITE_COUNT = colexp(_R, "favoriteCount");
    public static final ColumnExp<Integer> BRAND_ID = colexp(_R, "brandId");
    public static final ColumnExp<Integer> BASIS_ID = colexp(_R, "basisId");
    public static final ColumnExp<Integer> DERIVATION_COUNT = colexp(_R, "derivationCount");
    // AUTO-GENERATED: FIELDS END

    public static final int SCHEMA_VERSION = 15;

    /** Converts this record to a runtime record. See {@link #toListingCard} for caveats.  */
    public static final Function<CatalogRecord,ListingCard> TO_CARD =
        new Function<CatalogRecord,ListingCard>() {
        public ListingCard apply (CatalogRecord record) {
            return record.toListingCard();
        }
    };

    /** A unique id assigned to this catalog listing. */
    @Id
    @GeneratedValue(generator="catalogId", strategy=GenerationType.TABLE, allocationSize=1)
    public int catalogId;

    /** The id of the listed item. */
    @Index(name="listedItemIndex")
    public int listedItemId;

    /** The id of the original item used to create this listing. */
    public int originalItemId;

    /** The time this item was listed in the catalog. */
    @Index(name="listDateIndex")
    public Timestamp listedDate;

    /** The type of currency this item is listed for. */
    public Currency currency;

    /** The cost to purchase a clone of this item. */
    public int cost;

    /** The pricing of this item; See {@link CatalogListing#pricing}. */
    public int pricing;

    /** The number of unit sales after which to adjust the price or delist this item. */
    public int salesTarget;

    /** The number of times this item has been purchased. */
    @Index(name="purchasesIndex")
    public int purchases;

    /** The number of times this item has been returned. */
    public int returns;

    /** The number of people who consider the listed item a favorite. */
    @Index(name="faveCountIndex")
    public int favoriteCount;

    /** A reference to the listed item. This value is not persisted. */
    @Transient
    public ItemRecord item;

    /** The groupId of the brand that's selling this item. */
    public int brandId;

    /** The id of the listing that is the basis of this item. */
    public int basisId;

    /** The number of items for which this listing is a base. */
    public int derivationCount;

    /**
     * Creates a runtime record from this persistent record.
     */
    public CatalogListing toListing ()
    {
        CatalogListing listing = new CatalogListing();
        listing.catalogId = catalogId;
        if (item != null) {
            listing.detail = new ItemDetail();
            listing.detail.item = item.toItem();
        }
        listing.originalItemId = originalItemId;
        listing.listedDate = new Date(listedDate.getTime());
        listing.pricing = pricing;
        listing.salesTarget = salesTarget;
        listing.purchases = purchases;
        listing.returns = returns;
        listing.favoriteCount = favoriteCount;
        listing.basisId = basisId;
        listing.derivationCount = derivationCount;
        return listing;
    }

    /**
     * Creates a runtime card record from this persistent record. The caller is responsible for
     * looking up and filling in the member names of the creators. This method inserts a blank name
     * with the appropriate id to make that process easier.
     */
    public ListingCard toListingCard ()
    {
        ListingCard card = new ListingCard();
        if (item != null) {
            card.itemType = item.getType();
            card.name = item.name;
            card.thumbMedia = item.getThumbMediaDesc();
            card.brand = (brandId != 0) ? new GroupName(null, brandId) : null;
            card.creator = new MemberName(null, item.creatorId); // name filled in by caller
            card.descrip = item.description;
            card.remixable = item.isRemixable();
            card.rating = item.getRating();
        }
        card.catalogId = catalogId;
        card.currency = currency;
        card.cost = cost;
        return card;
    }

    @Override
    public String toString ()
    {
        return StringUtil.fieldsToString(this);
    }
}
