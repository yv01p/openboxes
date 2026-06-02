import React, { useState } from 'react';

import _ from 'lodash';
import PropTypes from 'prop-types';
import { RiInformationLine } from 'react-icons/ri';
import { useSelector } from 'react-redux';

import PreferenceTypeModal from 'components/productSupplier/modals/PreferenceTypeModal';
import Translate from 'utils/Translate';

// Computes the cell label from the flat LQ2 preference refs. Robust against undefined/empty
// (the list row may have no preferences at all) — never reads .length on undefined.
const getLabel = (preferences, resolvePreferenceTypeName) => {
  if (!preferences?.length) {
    return {
      id: 'react.productSupplier.preferenceType.none.label',
      defaultMessage: 'None',
    };
  }
  if (preferences.length > 1) {
    return {
      id: 'react.productSupplier.preferenceType.multiple.label',
      defaultMessage: 'Multiple',
      icon: <RiInformationLine />,
      className: 'cell-content',
    };
  }
  // Single preference: resolve its preferenceType NAME from the options (client-side).
  return resolvePreferenceTypeName(preferences[0]?.preferenceTypeId);
};

const PreferenceTypeColumn = ({ preferences, productSupplierId }) => {
  const [preferenceTypeModalData, setPreferenceTypeModalData] = useState([]);

  // preferenceType options ({ id, value, label }) used to resolve a ref's preferenceTypeId -> name.
  const { preferenceTypeOptions } = useSelector((state) => ({
    preferenceTypeOptions: state.productSupplier.preferenceTypes,
  }));

  const resolvePreferenceTypeName = (preferenceTypeId) => {
    const option = preferenceTypeOptions?.find((type) => type?.id === preferenceTypeId);
    return option?.label ?? preferenceTypeId;
  };

  const label = getLabel(preferences, resolvePreferenceTypeName);

  // Reshape the flat refs into the modal's expected shape, resolving preferenceType names from
  // options. destinationParty name is not available from the flat contract — degrade to the id.
  const buildModalData = () => (preferences ?? []).map((preference) => ({
    preferenceType: { name: resolvePreferenceTypeName(preference?.preferenceTypeId) },
    destinationParty: preference?.destinationPartyId
      ? { id: preference.destinationPartyId, name: preference.destinationPartyId }
      : null,
  }));

  const onCellClick = () => {
    if (preferences?.length > 1) {
      setPreferenceTypeModalData(buildModalData());
    }
  };

  const closeModal = () => setPreferenceTypeModalData([]);

  return (
    <>
      <span
        className={label?.className}
        onClick={onCellClick}
        role="presentation"
      >
        {_.isObject(label)
          ? (
            <>
              <Translate id={label.id} defaultMessage={label.defaultMessage} />
              {' '}
              {label?.icon}
            </>
          )
          : label}
      </span>
      <PreferenceTypeModal
        productSupplierId={productSupplierId}
        isOpen={Boolean(preferenceTypeModalData.length)}
        modalData={preferenceTypeModalData}
        closeModal={closeModal}
      />
    </>
  );
};

export default PreferenceTypeColumn;

PreferenceTypeColumn.propTypes = {
  // Flat LQ2 preference refs: { id, preferenceTypeId, destinationPartyId }. May be undefined/empty.
  preferences: PropTypes.arrayOf(PropTypes.shape({
    id: PropTypes.string,
    preferenceTypeId: PropTypes.string,
    destinationPartyId: PropTypes.string,
  })),
  productSupplierId: PropTypes.string.isRequired,
};

PreferenceTypeColumn.defaultProps = {
  preferences: [],
};
