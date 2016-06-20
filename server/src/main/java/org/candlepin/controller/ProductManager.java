/**
 * Copyright (c) 2009 - 2012 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.controller;

import org.candlepin.common.config.Configuration;
import org.candlepin.model.Content;
import org.candlepin.model.ContentCurator;
import org.candlepin.model.Owner;
import org.candlepin.model.OwnerProductCurator;
import org.candlepin.model.Product;
import org.candlepin.model.ProductAttribute;
import org.candlepin.model.ProductContent;
import org.candlepin.model.ProductCurator;
import org.candlepin.model.dto.ContentData;
import org.candlepin.model.dto.ProductAttributeData;
import org.candlepin.model.dto.ProductContentData;
import org.candlepin.model.dto.ProductData;

import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

import org.hibernate.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;



/**
 * The ProductManager class provides methods for creating, updating and removing product instances
 * which also perform the cleanup and general maintenance necessary to keep product state in sync
 * with other objects which reference them.
 * <p></p>
 * The methods provided by this class are the prefered methods to use for CRUD operations on
 * products, to ensure product versioning and linking is handled properly.
 */
public class ProductManager {
    public static Logger log = LoggerFactory.getLogger(ProductManager.class);

    private ContentCurator contentCurator;
    private EntitlementCertificateGenerator entitlementCertGenerator;
    private OwnerProductCurator ownerProductCurator;
    private ProductCurator productCurator;

    @Inject
    public ProductManager(ContentCurator contentCurator,
        EntitlementCertificateGenerator entitlementCertGenerator, ProductCurator productCurator,
        OwnerProductCurator ownerProductCurator, Configuration config) {

        this.contentCurator = contentCurator;
        this.entitlementCertGenerator = entitlementCertGenerator;
        this.ownerProductCurator = ownerProductCurator;
        this.productCurator = productCurator;
    }

    /**
     * Creates a new Product for the given owner, potentially using a different version than the
     * entity provided if a matching entity has already been registered for another owner.
     *
     * @param productData
     *  A product DTO instance representing the product to create
     *
     * @param owner
     *  The owner for which to create the product
     *
     * @throws IllegalStateException
     *  if this method is called with an entity already exists in the backing database for the given
     *  owner
     *
     * @throws NullPointerException
     *  if the provided product entity is null
     *
     * @return
     *  a new Product instance representing the specified product for the given owner
     */
    public Product createProduct(ProductData productData, Owner owner) {
        if (productData == null) {
            throw new IllegalArgumentException("productData is null");
        }

        if (productData.getId() == null || productData.getName() == null) {
            throw new IllegalArgumentException("productData is incomplete");
        }

        // TODO: more validation here...?

        Product entity = new Product(productData.getId(), productData.getName());
        this.applyProductChanges(entity, productData, owner);

        return this.createProduct(entity, owner);
    }

    /**
     * Creates a new Product for the given owner, potentially using a different version than the
     * entity provided if a matching entity has already been registered for another owner.
     *
     * @param entity
     *  A Product instance representing the product to create
     *
     * @param owner
     *  The owner for which to create the product
     *
     * @throws IllegalStateException
     *  if this method is called with an entity already exists in the backing database for the given
     *  owner
     *
     * @throws NullPointerException
     *  if the provided product entity is null
     *
     * @return
     *  a new Product instance representing the specified product for the given owner
     */
    @Transactional
    public Product createProduct(Product entity, Owner owner) {
        log.debug("Creating new product for org: {}, {}", entity, owner);

        Product existing = this.ownerProductCurator.getProductById(owner, entity.getId());

        // TODO: FIXME:
        // There's a bug here where if changes are applied to an entity's collections, and then
        // this method is called, the check below will trigger an illegal state exception, but
        // Hibernate's helpful nature will have persisted the changes during the lookup above.
        // This needs to be re-written to use DTOs as the primary source of entity creation, rather
        // than a bolted-on utility method.

        if (existing != null) {
            // If we're doing an exclusive creation, this should be an error condition
            throw new IllegalStateException("Product has already been created");
        }

        // Check if we have an alternate version we can use instead.
        List<Product> alternateVersions = this.productCurator
            .getProductsByVersion(entity.getId(), entity.hashCode())
            .list();

        for (Product alt : alternateVersions) {
            if (alt.equals(entity)) {
                // If we're "creating" a product, we shouldn't have any other object references to
                // update for this product. Instead, we'll just add the new owner to the product.
                this.ownerProductCurator.mapProductToOwner(alt, owner);
                return alt;
            }
        }

        // No other owners have matching version of this product. Since it's net new, we set the
        // owners explicitly to the owner given to ensure we don't accidentally clobber other owner
        // mappings
        entity = this.productCurator.create(entity);
        this.ownerProductCurator.mapProductToOwner(entity, owner);

        return entity;
    }

    // TODO: Remove this, probably
    // /**
    //  * Calculates the hash code of the given product entity as if the changes provided by update
    //  * were applied.
    //  *
    //  * @param entity
    //  *  The base entity onto which the updates will be projected
    //  *
    //  * @param update
    //  *  A product DTO containing the changes to project onto this product during hashcode
    //  *  calculation
    //  *
    //  * @param owner
    //  *  The owner to use when resolving content
    //  *
    //  * @return
    //  *  the hashcode of this product with the projected updates
    //  */
    // private int projectedHashCode(Product entity, ProductData update, Owner owner) {
    //     // TODO:
    //     // Eventually content should be considered a property of products (ala attributes), so we
    //     // don't have to do this annoying, nested projection and owner passing. Also, it would
    //     // solve the issue of forcing content to have only one instance per owner and this logic
    //     // could live in Product, where it belongs.
    //     if (entity == null) {
    //         throw new IllegalArgumentException("entity is null");
    //     }

    //     if (update == null) {
    //         throw new IllegalArgumentException("update is null");
    //     }

    //     if (owner == null) {
    //         throw new IllegalArgumentException("owner is null");
    //     }

    //     // Impl note:
    //     // The order and elements compared here needs to be 1:1 with the standard hashCode method.
    //     HashCodeBuilder builder = new HashCodeBuilder(37, 7)
    //         .append(entity.getId())
    //         .append(update.getName() != null ? update.getName() : entity.getName())
    //         .append(update.getMultiplier() != null ? update.getMultiplier() : entity.getMultiplier())
    //         .append(entity.isLocked());

    //     if (update.getAttributes() != null) {
    //         for (ProductAttributeData attrib : update.getAttributes()) {
    //             builder.append(attrib.getName());
    //             builder.append(attrib.getValue());
    //         }
    //     }
    //     else {
    //         for (ProductAttribute attrib : entity.getAttributes()) {
    //             builder.append(attrib.getName());
    //             builder.append(attrib.getValue());
    //         }
    //     }

    //     Collection<String> dependentProductIds = update.getDependentProductIds() != null ?
    //         update.getDependentProductIds() :
    //         entity.getDependentProductIds();

    //     for (String pid : dependentProductIds) {
    //         builder.append(pid);
    //     }

    //     if (update.getProductContent() != null) {
    //         // I apologize to future maintainers of this code
    //         for (ProductContentData pcd : update.getProductContent()) {
    //             ContentData contentData = pcd.getContent();

    //             if (contentData == null || contentData.getId() == null) {
    //                 // Content DTO is in a bad state, so we can't possibly compare it against
    //                 // anything.
    //                 throw new IllegalStateException("product contains content without an identifier");
    //             }

    //             ProductContent existingLink = this.getProductContent(contentData.getId());

    //             if (existingLink == null) {
    //                 // New product-content link. We need to find the content and project the
    //                 // content changes onto it.
    //                 Content existing = this.contentCurator.lookupById(owner, contentData.getId());

    //                 if (existing == null) {
    //                     // Content doesn't exist yet -- it should have been created already
    //                     throw new IllegalStateException("product references content which does not exist");
    //                 }

    //                 builder.append(existing.projectedHashCode(contentData));
    //                 builder.append(pcd.isEnabled() != null ? pcd.isEnabled() : Boolean.FALSE);
    //             }
    //             else {
    //                 builder.append(existingLink.getContent().projectedHashCode(contentData));
    //                 builder.append(pcd.isEnabled() != null ? pcd.isEnabled() : existingLink.isEnabled());
    //             }
    //         }
    //     }
    //     else {
    //         for (ProductContent productContent : entity.getProductContent()) {
    //             Content content = productContent.getContent();

    //             builder.append(productContent.getContent().hashCode());
    //             builder.append(productContent.isEnabled());
    //         }
    //     }

    //     return builder.toHashCode();
    // }

    private Product applyProductChanges(Product entity, ProductData update, Owner owner) {
        // TODO:
        // Eventually content should be considered a property of products (ala attributes), so we
        // don't have to do this annoying, nested projection and owner passing. Also, it would
        // solve the issue of forcing content to have only one instance per owner and this logic
        // could live in Product, where it belongs.
        if (entity == null) {
            throw new IllegalArgumentException("entity is null");
        }

        if (update == null) {
            throw new IllegalArgumentException("update is null");
        }

        if (owner == null) {
            throw new IllegalArgumentException("owner is null");
        }

        if (update.getName() != null) {
            entity.setName(update.getName());
        }

        if (update.getMultiplier() != null) {
            entity.setMultiplier(update.getMultiplier());
        }

        if (update.getAttributes() != null) {
            Collection<ProductAttribute> attributes = new LinkedList<ProductAttribute>();

            for (ProductAttributeData attrib : update.getAttributes()) {
                if (attrib == null || attrib.getName() == null) {
                    throw new IllegalStateException("attribute is null or incomplete");
                }

                ProductAttribute existing = entity.getAttribute(attrib.getName());

                if (existing != null) {
                    existing.populate(attrib);
                    attributes.add(existing);
                }
                else {
                    attributes.add(new ProductAttribute(attrib));
                }
            }

            entity.setAttributes(attributes);
        }

        if (update.getProductContent() != null) {
            Collection<ProductContent> productContent = new LinkedList<ProductContent>();

            for (ProductContentData pcd : update.getProductContent()) {
                if (pcd == null || pcd.getContent() == null || pcd.getContent().getId() == null) {
                    throw new IllegalStateException("product content is null or incomplete");
                }

                ContentData contentData = pcd.getContent();
                ProductContent existingLink = entity.getProductContent(contentData.getId());

                if (existingLink == null) {
                    Content existing = this.contentCurator.lookupById(owner, contentData.getId());

                    if (existing == null) {
                        // Content doesn't exist yet -- it should have been created already
                        throw new IllegalStateException("product references content which does not exist");
                    }

                    existingLink = new ProductContent(entity, existing, Boolean.FALSE);
                }

                existingLink.getContent().populate(contentData);

                if (pcd.isEnabled() != null) {
                    existingLink.setEnabled(pcd.isEnabled());
                }

                productContent.add(existingLink);
            }

            entity.setProductContent(productContent);
        }

        if (update.getDependentProductIds() != null) {
            entity.setDependentProductIds(update.getDependentProductIds());
        }

        return entity;
    }

    public Product updateProduct(ProductData update, Owner owner, boolean regenerateEntitlementCerts) {
        if (update == null) {
            throw new IllegalArgumentException("update is null");
        }

        if (update.getId() == null) {
            throw new IllegalArgumentException("update does not define a product id");
        }

        if (owner == null) {
            throw new IllegalArgumentException("owner");
        }

        Product entity = this.ownerProductCurator.getProductById(owner.getId(), update.getId());

        if (entity == null) {
            // If we're doing an exclusive update, this should be an error condition
            throw new IllegalStateException("Product has not yet been created");
        }

        return this.updateProduct(entity, update, owner, regenerateEntitlementCerts);
    }

    /**
     * Updates the specified product instance, creating a new version of the product as necessary.
     * The product instance returned by this method is not guaranteed to be the same instance passed
     * in. As such, once this method has been called, callers should only use the instance output by
     * this method.
     *
     * @param entity
     *  The product entity to update
     *
     * @param update
     *  The product updates to apply
     *
     * @param owner
     *  The owner for which to update the product
     *
     * @param regenerateEntitlementCerts
     *  Whether or not changes made to the product should trigger the regeneration of entitlement
     *  certificates for affected consumers
     *
     * @throws IllegalStateException
     *  if this method is called with an entity does not exist in the backing database for the given
     *  owner
     *
     * @throws IllegalArgumentException
     *  if either the provided product entity or owner are null
     *
     * @return
     *  the updated product entity, or a new product entity
     */
    @Transactional
    public Product updateProduct(Product entity, ProductData update, Owner owner,
        boolean regenerateEntitlementCerts) {

        log.debug("Applying product update for org: {} => {}, {}", update, entity, owner);

        if (entity == null) {
            throw new IllegalArgumentException("entity is null");
        }

        if (update == null) {
            throw new IllegalArgumentException("update is null");
        }

        if (owner == null) {
            throw new IllegalArgumentException("owner is null");
        }

        Product updated = this.applyProductChanges((Product) entity.clone(), update, owner);

        // TODO:
        // We, currently, do not trigger a refresh after updating a product. At present this is an
        // exercise left to the caller, but perhaps we should be doing that here automatically?

        // Check for newer versions of the same product. We want to try to dedupe as much data as we
        // can, and if we have a newer version of the product (which matches the version provided by
        // the caller), we can just point the given orgs to the new product instead of giving them
        // their own version.
        // This is probably going to be a very expensive operation, though.
        List<Product> alternateVersions = this.productCurator
            .getProductsByVersion(update.getId(), updated.hashCode())
            .list();

        log.debug("Checking {} alternate product versions", alternateVersions.size());
        for (Product alt : alternateVersions) {
            // Skip ourselves if we happen across it
            if (alt != updated && alt.equals(updated)) {
                log.debug("Converging product with existing: {} => {}", updated, alt);

                List<Owner> owners = Arrays.asList(owner);

                updated = this.ownerProductCurator.updateOwnerProductReferences(updated, alt, owners);

                if (regenerateEntitlementCerts) {
                    this.entitlementCertGenerator.regenerateCertificatesOf(
                        owners, Arrays.asList(updated), true
                    );
                }

                return updated;
            }
        }

        // No alternate versions with which to converge. Check if we can do an in-place update instead
        if (this.ownerProductCurator.getOwnerCount(updated) == 1) {
            log.debug("Applying in-place update to product: {}", updated);

            updated = this.productCurator.merge(updated);

            if (regenerateEntitlementCerts) {
                this.entitlementCertGenerator.regenerateCertificatesOf(Arrays.asList(updated), true);
            }

            return updated;
        }

        // Product is shared by multiple owners; we have to diverge here
        log.debug("Forking product and applying update: {}", updated);

        // This org isn't the only org using the product. We need to create a new product
        // instance and move the org over to the new product.
        List<Owner> owners = Arrays.asList(owner);

        // Clear the UUID so Hibernate doesn't think our copy is a detached entity
        updated.setUuid(null);

        updated = this.productCurator.create(updated);
        updated = this.ownerProductCurator.updateOwnerProductReferences(entity, updated, owners);

        if (regenerateEntitlementCerts) {
            this.entitlementCertGenerator.regenerateCertificatesOf(
                owners, Arrays.asList(updated), true
            );
        }

        return updated;
    }

    /**
     * Removes the specified product from the given owner. If the product is in use by multiple
     * owners, the product will not actually be deleted, but, instead, will simply by removed from
     * the given owner's visibility.
     *
     * @param entity
     *  The product entity to remove
     *
     * @param owner
     *  The owner for which to remove the product
     *
     * @throws IllegalStateException
     *  if this method is called with an entity does not exist in the backing database for the given
     *  owner, or if the product is currently in use by one or more subscriptions/pools
     *
     * @throws NullPointerException
     *  if the provided product entity is null
     */
    public void removeProduct(Product entity, Owner owner) {
        log.debug("Removing product from owner: {}, {}", entity, owner);

        if (entity == null) {
            throw new NullPointerException("entity");
        }

        // This has to fetch a new instance, or we'll be unable to compare the objects
        Product existing = this.ownerProductCurator.getProductById(owner, entity.getId());

        if (existing == null) {
            // If we're doing an exclusive update, this should be an error condition
            throw new IllegalStateException("Product has not yet been created");
        }

        if (this.productCurator.productHasSubscriptions(existing, owner)) {
            throw new IllegalStateException("Product is currently in use by one or more pools");
        }

        this.ownerProductCurator.removeOwnerFromProduct(existing, owner);
        if (this.ownerProductCurator.getOwnerCount(existing) == 0) {
            // No one is using this product anymore; delete the entity
            this.productCurator.delete(existing);
        }
        else {
            // Clean up any dangling references to content
            this.ownerProductCurator.removeOwnerProductReferences(existing, Arrays.asList(owner));
        }
    }


    // TODO:
    // Not sure if we'll need this or not. Don't feel like writing a test for it at the moment, so
    // I'm leaving it disabled until we have a need for it. -C
    // public Product addContentToProduct(Product product, Collection<Content> content, boolean enabled,
    //     Owner owner, boolean regenerateEntitlementCerts) {

    //     Collection<ProductContent> productContent = new LinkedList<ProductContent>();

    //     for (Content current : content) {
    //         productContent.add(new ProductContent(product, current, enabled));
    //     }

    //     return this.addContentToProduct(product, productContent, owner, regenerateEntitlementCerts);
    // }

    /**
     * Adds the specified content to the product, effective for the given owner. If the product is
     * already mapped to one of the content instances provided, the mapping will be updated to
     * reflect the configuration of the mapping provided.
     *
     * @param product
     *  The product to which content should be added
     *
     * @param content
     *  A collection of ProductContent instances referencing the content to add to the product
     *
     * @param owner
     *  The owner of the product to update
     *
     * @param regenerateEntitlementCerts
     *  Whether or not changes made to the product should trigger the regeneration of entitlement
     *  certificates for affected consumers
     *
     * @return
     *  the updated product entity, or a new product entity
     */
    public Product addContentToProduct(Product product, Collection<ProductContent> content, Owner owner,
        boolean regenerateEntitlementCerts) {

        if (this.ownerProductCurator.isProductMappedToOwner(product, owner)) {
            ProductData update = product.toDTO();
            boolean changed = false;

            for (ProductContent add : content) {
                changed |= update.addProductContent(add);
            }

            if (changed) {
                product = this.updateProduct(product, update, owner, regenerateEntitlementCerts);
            }
        }

        return product;
    }

    /**
     * Removes the specified content from the given product for a single owner. The changes made to
     * the product may result in the convergence or divergence of product versions.
     *
     * @param product
     *  the product from which to remove content
     *
     * @param content
     *  the content to remove
     *
     * @param owner
     *  the owner for which the change should take effect
     *
     * @param regenerateEntitlementCerts
     *  Whether or not changes made to the product should trigger the regeneration of entitlement
     *  certificates for affected consumers
     *
     * @return
     *  the updated product instance
     */
    public Product removeProductContent(Product product, Collection<Content> content, Owner owner,
        boolean regenerateEntitlementCerts) {

        if (this.ownerProductCurator.isProductMappedToOwner(product, owner)) {
            ProductData update = product.toDTO();
            boolean changed = false;

            for (Content remove : content) {
                changed |= update.removeContent(remove);
            }

            if (changed) {
                product = this.updateProduct(product, update, owner, regenerateEntitlementCerts);
            }
        }

        return product;
    }
}
