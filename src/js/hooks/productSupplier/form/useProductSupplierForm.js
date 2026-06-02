import { zodResolver } from '@hookform/resolvers/zod';
import _ from 'lodash';
import moment from 'moment';
import { useForm } from 'react-hook-form';
import { useDispatch, useSelector } from 'react-redux';
import { useHistory, useParams } from 'react-router-dom';

import {
  fetchPreferenceTypes,
  fetchQuantityUnitOfMeasure,
  fetchRatingTypeCodes,
  hideSpinner,
  showSpinner,
} from 'actions';
import productApi from 'api/services/ProductApi';
import productAttributeApi from 'api/services/ProductAttributeApi';
import productPackageApi from 'api/services/ProductPackageApi';
import productPriceApi from 'api/services/ProductPriceApi';
import productSupplierApi from 'api/services/ProductSupplierApi';
import productSupplierAttributeApi from 'api/services/ProductSupplierAttributeApi';
import productSupplierPreferenceApi from 'api/services/ProductSupplierPreferenceApi';
import notification from 'components/Layout/notifications/notification';
import { PRODUCT_SUPPLIER_URL } from 'consts/applicationUrls';
import NotificationType from 'consts/notificationTypes';
import useOptionsFetch from 'hooks/options-data/useOptionsFetch';
import useCalculateEachPrice from 'hooks/productSupplier/form/useCalculateEachPrice';
import useProductSupplierAttributes from 'hooks/productSupplier/form/useProductSupplierAttributes';
import useProductSupplierValidation from 'hooks/productSupplier/form/useProductSupplierValidation';
import useQueryParams from 'hooks/useQueryParams';
import useTranslateWithRedirect from 'hooks/useTranslateWithRedirect';
import { omitEmptyValues } from 'utils/form-values-utils';
import { splitPreferenceTypes } from 'utils/list-utils';

const useProductSupplierForm = () => {
  const { validationSchema } = useProductSupplierValidation();
  const { mapFetchedAttributes } = useProductSupplierAttributes();
  // Check if productSupplierId is provided in the URL (determine whether it is create or edit)
  const { productSupplierId } = useParams();
  const queryParams = useQueryParams();

  const history = useHistory();
  const translateWithRedirect = useTranslateWithRedirect();
  const dispatch = useDispatch();

  // Option lists used to resolve flat-DTO ids back into human-readable labels on the edit path.
  // The flat catalog contract returns only ids for uom / preferenceType, so we map them against the
  // options the form already loads from redux (same source the subsection selects use).
  const { quantityUom, preferenceTypeOptions } = useSelector((state) => ({
    quantityUom: state.unitOfMeasure.quantity,
    preferenceTypeOptions: state.productSupplier.preferenceTypes,
  }));

  useOptionsFetch(
    [fetchRatingTypeCodes, fetchPreferenceTypes, fetchQuantityUnitOfMeasure],
    { refetchOnLocaleChange: false },
  );

  // Resolve a UOM label from the quantity-UoM options (catalog flat shape: { id, name, code }).
  // Falls back to the id when the option list is not yet loaded.
  const resolveUomLabel = (uomId) => {
    const option = quantityUom?.find((uom) => uom?.id === uomId);
    return option?.name ?? option?.label ?? uomId;
  };

  // Resolve a preferenceType label from the preferenceType options ({ id, value, label }).
  // Falls back to the id when the option is not present.
  const resolvePreferenceTypeLabel = (preferenceTypeId) => {
    const option = preferenceTypeOptions?.find((type) => type?.id === preferenceTypeId);
    return option?.label ?? preferenceTypeId;
  };

  // Fetches product supplier to edit and returns default values that should be set.
  // Assembles the form's existing view-model from the FLAT catalog contract via concurrent GETs:
  //   - productSuppliers/{id} (flat: product/supplier/manufacturer LABELS are populated on by-id)
  //   - productPackages/{defaultProductPackageId} -> uom/quantity/productPriceId
  //   - productPrices/{productPriceId}            -> default package price value
  //   - productPrices/{contractPriceId}           -> contract price value + validity (toDate)
  //   - productSupplierPreferences?productSupplier={id} -> flat preference rows
  //   - productAttributes (filtered client-side by productSupplierId) -> saved attribute values
  const getProductSupplier = async () => {
    const response = await productSupplierApi.getProductSupplier(productSupplierId);
    const productSupplier = response?.data?.data;

    // Independent flat GETs — run concurrently. Each is conditional on the relevant FK id existing.
    const [
      packageResponse,
      contractPriceResponse,
      preferencesResponse,
      attributesResponse,
    ] = await Promise.all([
      productSupplier?.defaultProductPackageId
        ? productPackageApi.getById(productSupplier.defaultProductPackageId)
        : Promise.resolve(null),
      productSupplier?.contractPriceId
        ? productPriceApi.getById(productSupplier.contractPriceId)
        : Promise.resolve(null),
      productSupplierPreferenceApi.getByProductSupplier(productSupplierId),
      productAttributeApi.getByProductSupplier(productSupplierId),
    ]);

    const defaultProductPackage = packageResponse?.data?.data ?? null;
    // The default package's own price (productPriceId) carries the package price VALUE — the
    // package DTO's price fields are write-only (always null on GET), so we fetch the ProductPrice.
    const packagePriceResponse = defaultProductPackage?.productPriceId
      ? await productPriceApi.getById(defaultProductPackage.productPriceId)
      : null;
    const packagePrice = packagePriceResponse?.data?.data ?? null;
    const contractPrice = contractPriceResponse?.data?.data ?? null;

    // Preferences: flat rows { id, productSupplierId, destinationPartyId, preferenceTypeId, ... }.
    // Map to the nested view-model the form/splitPreferenceTypes expect (default = no destination).
    const flatPreferences = preferencesResponse?.data?.data ?? [];
    const mappedPreferences = flatPreferences.map((preference) => ({
      ...preference,
      // destinationParty name is not in the flat contract — degrade to the id (ACCEPTED).
      destinationParty: preference?.destinationPartyId
        ? { id: preference.destinationPartyId, name: preference.destinationPartyId }
        : null,
      preferenceType: {
        id: preference?.preferenceTypeId,
        name: resolvePreferenceTypeLabel(preference?.preferenceTypeId),
      },
    }));
    const {
      preferenceTypes,
      defaultPreferenceType,
    } = splitPreferenceTypes(mappedPreferences);

    // Attributes: the flat productSupplier DTO no longer nests them. The catalog GET-only
    // /api/productAttributes?productSupplier={id} returns just this supplier's rows; reshape each
    // flat row { attributeId, value } into the { attribute: { id }, value } shape the form wants.
    const supplierAttributes = (attributesResponse?.data?.data ?? [])
      .map((attribute) => ({
        attribute: { id: attribute?.attributeId },
        value: attribute?.value,
      }));
    const attributes = mapFetchedAttributes(supplierAttributes);

    return {
      id: productSupplier?.id ?? undefined,
      basicDetails: {
        code: productSupplier?.code ?? undefined,
        product: {
          id: productSupplier?.productId,
          value: productSupplier?.productId,
          label: `${productSupplier?.productCode} - ${productSupplier?.productName}`,
        },
        productCode: productSupplier?.productCode ?? undefined,
        supplier: productSupplier?.supplierId
          ? {
            id: productSupplier?.supplierId,
            value: productSupplier?.supplierId,
            label: `${productSupplier?.supplierCode} ${productSupplier?.supplierName}`,
          } : undefined,
        supplierCode: productSupplier?.supplierCode ?? undefined,
        name: productSupplier?.name ?? undefined,
        active: productSupplier?.active,
        dateCreated: productSupplier?.dateCreated ?? undefined,
        lastUpdated: productSupplier?.lastUpdated ?? undefined,
        // Flat contract exposes only audit ids (no user names) — names degrade to undefined.
        createdBy: {
          id: productSupplier?.createdById ?? undefined,
          name: undefined,
        },
        updatedBy: {
          id: productSupplier?.updatedById ?? undefined,
          name: undefined,
        },
      },
      additionalDetails: {
        manufacturer: productSupplier?.manufacturerId
          ? {
            id: productSupplier?.manufacturerId,
            value: productSupplier?.manufacturerId,
            label: productSupplier?.manufacturerName,
          }
          : undefined,
        ratingTypeCode: productSupplier?.ratingTypeCode
          ? {
            id: productSupplier?.ratingTypeCode,
            value: productSupplier?.ratingTypeCode,
            label: productSupplier?.ratingTypeCode,
          }
          : undefined,
        manufacturerCode: productSupplier?.manufacturerCode ?? undefined,
        brandName: productSupplier?.brandName ?? undefined,
      },
      productSupplierPreferences: preferenceTypes.map((preferenceType) => ({
        ...preferenceType,
        destinationParty: {
          id: preferenceType.destinationParty?.id,
          label: preferenceType.destinationParty?.name,
          value: preferenceType.destinationParty?.id,
        },
        preferenceType: {
          id: preferenceType.preferenceType?.id,
          label: preferenceType.preferenceType?.name,
          value: preferenceType.preferenceType?.id,
        },
      })),
      packageSpecification: {
        uom: defaultProductPackage?.uomId
          ? {
            id: defaultProductPackage?.uomId,
            value: defaultProductPackage?.uomId,
            label: resolveUomLabel(defaultProductPackage?.uomId),
          }
          : undefined,
        productPackageQuantity: defaultProductPackage?.quantity ?? undefined,
        minOrderQuantity: productSupplier?.minOrderQuantity ?? undefined,
        productPackagePrice: packagePrice?.price ?? undefined,
        eachPrice: productSupplier?.eachPrice ?? undefined,
      },
      fixedPrice: {
        contractPricePrice: contractPrice?.price ?? undefined,
        // contractPriceValidUntil maps to ProductPrice.toDate (catalog Deviation #2 — there is no
        // valid_until column; the package save persists contractPriceValidUntil into to_date).
        contractPriceValidUntil: contractPrice?.toDate ?? undefined,
        tieredPricing: productSupplier?.tieredPricing ?? undefined,
      },
      attributes,
      defaultPreferenceType: {
        id: defaultPreferenceType?.id ?? undefined,
        preferenceType: !_.isEmpty(defaultPreferenceType) ? {
          id: defaultPreferenceType?.preferenceType?.id,
          label: defaultPreferenceType?.preferenceType?.name,
          value: defaultPreferenceType?.preferenceType?.id,
        } : undefined,
        validityStartDate: defaultPreferenceType?.validityStartDate ?? undefined,
        validityEndDate: defaultPreferenceType?.validityEndDate ?? undefined,
        comments: defaultPreferenceType?.comments ?? undefined,
      },
    };
  };

  const initializeDefaultValues = async () => {
    if (productSupplierId) {
      return getProductSupplier();
    }

    if (queryParams.productId) {
      // Create-from-product: read the flat catalog Product by-id (data: id, productCode, name).
      const productResponse = await productApi.getCatalogProduct(queryParams.productId);
      const product = productResponse?.data?.data;
      return {
        basicDetails: {
          active: true,
          product: {
            id: product?.id,
            value: product?.id,
            label: `${product?.productCode} - ${product?.name}`,
          },
        },
        fixedPrice: {
          tieredPricing: false,
        },
        productSupplierPreferences: [],
      };
    }

    return {
      basicDetails: {
        active: true,
      },
      fixedPrice: {
        tieredPricing: false,
      },
      productSupplierPreferences: [],
    };
  };

  const {
    control,
    handleSubmit,
    trigger,
    setValue,
    formState: { errors, isValid, isDirty },
    getValues,
  } = useForm({
    // We want the validation errors to occur onBlur of any field
    mode: 'onBlur',
    // If there is a productSupplier param, it means we are editing a product supplier, so fetch it,
    // otherwise the only default value should be the active field
    defaultValues: initializeDefaultValues,
    resolver: (values, context, options) =>
      zodResolver(validationSchema(values))(values, context, options),
  });

  useCalculateEachPrice({ control, setValue });

  const buildDetailsPayload = ({
    basicDetails, additionalDetails, tieredPricing, minOrderQuantity,
  }) => {
    const { product, supplier } = basicDetails;
    const { manufacturer, ratingTypeCode } = additionalDetails;

    // Flat catalog ProductSupplierDto keys ONLY — the catalog rejects unknown JSON props
    // (Jackson FAIL_ON_UNKNOWN_PROPERTIES is at its default). Drop the nested select objects
    // (product/supplier/manufacturer/ratingTypeCode) and the read-only audit objects
    // (createdBy/updatedBy). minOrderQuantity is a SUPPLIER field (the form keeps it under
    // packageSpecification, but it persists on the supplier, NOT the package) so it is routed here.
    return {
      ..._.omit(omitEmptyValues(basicDetails), [
        'product', 'supplier', 'createdBy', 'updatedBy', 'dateCreated', 'lastUpdated',
      ]),
      ..._.omit(omitEmptyValues(additionalDetails), ['manufacturer', 'ratingTypeCode']),
      productId: product ? product.id : null,
      supplierId: supplier ? supplier.id : null,
      manufacturerId: manufacturer ? manufacturer.id : null,
      ratingTypeCode: ratingTypeCode ? ratingTypeCode.id : null,
      minOrderQuantity: minOrderQuantity ?? null,
      tieredPricing,
    };
  };

  // Builds a single flat catalog ProductSupplierPreferenceDto from a form preference view-model.
  // Validity dates are sent as ISO-8601 Instants (catalog DTO fields are Instant, not MM/DD/YYYY).
  const buildPreferenceItem = (preference, productSupplier) => ({
    id: preference?.id,
    productSupplierId: productSupplier,
    destinationPartyId: preference?.destinationParty?.id ?? null,
    preferenceTypeId: preference?.preferenceType?.id ?? null,
    comments: preference?.comments ?? null,
    validityStartDate: preference?.validityStartDate
      ? moment(preference.validityStartDate).toISOString()
      : null,
    validityEndDate: preference?.validityEndDate
      ? moment(preference.validityEndDate).toISOString()
      : null,
  });

  const buildPreferencesPayload = ({
    defaultPreferenceType,
    productSupplierPreferences,
    productSupplier,
  }) => {
    // Map the table variations + the default preference type into flat catalog items.
    const preferenceVariations = (productSupplierPreferences ?? [])
      .map((preference) => buildPreferenceItem(preference, productSupplier));
    const defaultMapped = buildPreferenceItem(defaultPreferenceType, productSupplier);

    // Combine variations + default into a single list. Filter out elements with no value filled
    // (ignore productSupplierId and id, which are always present).
    const preferencesCombined = [
      defaultMapped,
      ...preferenceVariations,
    ].filter((preference) =>
      _.some(Object.values(_.omit(preference, 'productSupplierId', 'id'))));

    // The catalog batch POST takes a RAW ARRAY body (List<ProductSupplierPreferenceDto>),
    // NOT the Grails { productSupplierPreferences: [...] } envelope.
    return preferencesCombined;
  };

  const buildPackagePayload = ({
    packageSpecification, fixedPrice, productSupplier, productId,
  }) => {
    const { uom, productPackageQuantity, productPackagePrice } = packageSpecification;
    // Send ONLY flat catalog ProductPackageDto fields — the catalog rejects unknown JSON props
    // (Jackson FAIL_ON_UNKNOWN_PROPERTIES default). Explicitly EXCLUDE non-DTO fields the form
    // keeps under packageSpecification/fixedPrice: minOrderQuantity (a SUPPLIER field, sent via
    // details), eachPrice (a derived display-only value), tieredPricing (also a details field).
    // productId feeds the service's (product, productSupplier, uom, quantity) upsert key. The price
    // VALUE fields are write-only inputs the service materializes into ProductPrice rows;
    // contractPriceValidUntil maps to ProductPrice.toDate, sent as an ISO-8601 Instant.
    return {
      productId: productId ?? null,
      productSupplierId: productSupplier,
      uomId: uom ? uom.id : null,
      quantity: productPackageQuantity ?? null,
      productPackagePrice: productPackagePrice ?? null,
      contractPricePrice: fixedPrice?.contractPricePrice ?? null,
      contractPriceValidUntil: fixedPrice?.contractPriceValidUntil
        ? moment(fixedPrice?.contractPriceValidUntil).toISOString()
        : null,
    };
  };

  const buildAttributesPayload = ({ attributes, productSupplier }) => {
    const attributesMapped = Object.entries(attributes).map(([attributeId, values]) => ({
      attribute: attributeId,
      productSupplier,
      value: values?.value ?? values ?? '',
    }));

    return {
      productAttributes: attributesMapped,
    };
  };

  const onSubmit = async (values) => {
    const {
      basicDetails,
      additionalDetails,
      defaultPreferenceType,
      packageSpecification,
      fixedPrice,
      attributes,
      productSupplierPreferences,
    } = values;

    const { tieredPricing } = fixedPrice;

    // First build the details payload. minOrderQuantity lives under packageSpecification in the
    // form but persists on the SUPPLIER (ProductSupplierDto), so it is routed into details.
    const detailsPayload = buildDetailsPayload({
      basicDetails,
      additionalDetails,
      tieredPricing,
      minOrderQuantity: packageSpecification?.minOrderQuantity,
    });
    // Either create or update an existing product supplier details
    try {
      dispatch(showSpinner());
      const detailsResponse = productSupplierId
        ? await productSupplierApi.updateDetails(detailsPayload, productSupplierId)
        : await productSupplierApi.saveDetails(detailsPayload);

      // Id of created/updated product supplier
      const productSupplier = detailsResponse.data?.data?.id;
      const productSupplierCode = detailsResponse.data?.data?.code;

      // Build package and pricing payload and send a request
      const packagePayload = buildPackagePayload({
        packageSpecification,
        fixedPrice,
        productSupplier,
        productId: basicDetails?.product?.id,
      });
      await productPackageApi.save(packagePayload);

      // Build preferences payload (a RAW flat array) and, if not empty, send the batch request.
      const preferencesPayload = buildPreferencesPayload({
        defaultPreferenceType,
        productSupplierPreferences,
        productSupplier,
      });

      if (preferencesPayload.length) {
        await productSupplierPreferenceApi.saveOrUpdateBatch(preferencesPayload);
      }

      // Build attributes payload and send a request
      const attributesPayload = buildAttributesPayload({ attributes, productSupplier });
      await productSupplierAttributeApi.updateAttributes(attributesPayload);

      // Show a success message and redirect to the list page
      const successMessage = translateWithRedirect({
        label: `react.productSupplier.form.success.${productSupplierId ? 'update' : 'create'}`,
        defaultLabel: `Product source ${productSupplierCode} has been ${productSupplierId ? 'updated' : 'created'} successfully`,
        options: { code: productSupplierCode },
        redirects: [{
          phrase: productSupplierCode,
          redirectTo: PRODUCT_SUPPLIER_URL.edit(productSupplier),
        }],
      });

      notification(NotificationType.SUCCESS)({ message: successMessage });
      history.push(PRODUCT_SUPPLIER_URL.list());
    } finally {
      dispatch(hideSpinner());
    }
  };

  // preselect value 1 when unit of measure Each is selected
  const setProductPackageQuantity = (unitOfMeasure) => {
    if (unitOfMeasure?.id === 'EA') {
      setValue('packageSpecification.productPackageQuantity', 1, { shouldValidate: true });
      return;
    }
    setValue('packageSpecification.productPackageQuantity', '');
  };

  return {
    control,
    handleSubmit,
    errors,
    isValid,
    isFormDirty: isDirty,
    triggerValidation: trigger,
    onSubmit,
    setProductPackageQuantity,
    setValue,
    getValues,
  };
};

export default useProductSupplierForm;
