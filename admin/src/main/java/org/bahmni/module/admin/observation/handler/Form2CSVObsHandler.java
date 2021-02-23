package org.bahmni.module.admin.observation.handler;

import org.bahmni.csv.KeyValue;
import org.bahmni.module.admin.csv.models.EncounterRow;
import org.bahmni.module.admin.csv.utils.CSVUtils;
import org.bahmni.module.admin.observation.CSVObservationHelper;
import org.bahmni.form2.service.FormFieldPathService;
import org.openmrs.api.APIException;
import org.openmrs.module.emrapi.encounter.domain.EncounterTransaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.bahmni.module.admin.observation.CSVObservationHelper.getLastItem;
import static org.springframework.util.CollectionUtils.isEmpty;

@Component
public class Form2CSVObsHandler implements CSVObsHandler {

    private static final String FORM_NAMESPACE = "Bahmni";
    private static final String FORM_FIELD_PATH_SEPARATOR = "/";
    private static final String DATE = "Date";

    private CSVObservationHelper csvObservationHelper;
    private FormFieldPathService formFieldPathService;

    @Autowired
    public Form2CSVObsHandler(CSVObservationHelper csvObservationHelper, FormFieldPathService formFieldPathService) {
        this.csvObservationHelper = csvObservationHelper;
        this.formFieldPathService = formFieldPathService;
    }

    @Override
    public List<KeyValue> getRelatedCSVObs(EncounterRow encounterRow) {
        return encounterRow.obsRows.stream().filter(csvObservation -> csvObservationHelper.isForm2Type(csvObservation))
                .collect(Collectors.toList());
    }

    @Override
    public List<EncounterTransaction.Observation> handle(EncounterRow encounterRow) throws ParseException {
        List<EncounterTransaction.Observation> form2Observations = new ArrayList<>();
        List<KeyValue> form2CSVObservations = getRelatedCSVObs(encounterRow);
        for (KeyValue form2CSVObservation : form2CSVObservations) {
            if (isNotBlank(form2CSVObservation.getValue())) {
                final List<String> form2CSVHeaderParts = getCSVHeaderPartsByIgnoringForm2KeyWord(form2CSVObservation);
                verifyCSVHeaderHasConcepts(form2CSVObservation, form2CSVHeaderParts);
                csvObservationHelper.verifyNumericConceptValue(form2CSVObservation, form2CSVHeaderParts);
                csvObservationHelper.createObservations(form2Observations, encounterRow.getEncounterDate(),
                        form2CSVObservation, getConceptNames(form2CSVHeaderParts));
                setFormNamespaceAndFieldPath(form2Observations, form2CSVHeaderParts);
            }
        }
        return form2Observations;
    }

    @Override
    public List<EncounterTransaction.Observation> handle(EncounterRow encounterRow, boolean shouldPerformForm2Validations) throws ParseException {
        if(!shouldPerformForm2Validations)
            return handle(encounterRow);
        List<EncounterTransaction.Observation> form2Observations = new ArrayList<>();
        List<KeyValue> form2CSVObservations = getRelatedCSVObs(encounterRow);
        for (KeyValue form2CSVObservation : form2CSVObservations) {
            final List<String> form2CSVHeaderParts = getCSVHeaderPartsByIgnoringForm2KeyWord(form2CSVObservation);
            if (isNotBlank(form2CSVObservation.getValue())) {
                verifyCSVHeaderHasConcepts(form2CSVObservation, form2CSVHeaderParts);
                csvObservationHelper.verifyNumericConceptValue(form2CSVObservation, form2CSVHeaderParts);
                boolean isMultiSelectObs = formFieldPathService.isMultiSelectObs(form2CSVHeaderParts);
                boolean isAddmoreConceptObs = formFieldPathService.isAddmore(form2CSVHeaderParts);
                if(isMultiSelectObs) {
                    processMultiSelectObs(encounterRow, form2Observations, form2CSVObservation, form2CSVHeaderParts);
                } else if(isAddmoreConceptObs) {
                    processAddmoreConcept(encounterRow, form2Observations, form2CSVObservation, form2CSVHeaderParts);
                } else {
                    csvObservationHelper.createObservations(form2Observations, encounterRow.getEncounterDate(),
                            form2CSVObservation, getConceptNames(form2CSVHeaderParts));
                    setFormNamespaceAndFieldPath(form2Observations, form2CSVHeaderParts);
                    validateObsForFutureDate(form2Observations, form2CSVObservation, form2CSVHeaderParts);
                }
            } else {
                boolean mandatoryFieldMissing = formFieldPathService.isMandatory(form2CSVHeaderParts);
                if(mandatoryFieldMissing) {
                    throw new APIException(format("Empty value provided for mandatory field %s", form2CSVHeaderParts.get(form2CSVHeaderParts.size()-1)));
                }
            }
        }
        return form2Observations;
    }

    private void verifyCSVHeaderHasConcepts(KeyValue form2CSVObservation, List<String> form2CSVHeaderParts) {
        if (form2CSVHeaderParts.size() <= 1) {
            throw new APIException(format("No concepts found in %s", form2CSVObservation.getKey()));
        }
    }

    private void setFormNamespaceAndFieldPath(List<EncounterTransaction.Observation> form2Observations, List<String> form2CSVHeaderParts) {
        if (!isEmpty(form2Observations)) {
            final EncounterTransaction.Observation observation = getLastItem(form2Observations);
            final String formFieldPath = formFieldPathService.getFormFieldPath(form2CSVHeaderParts);
            observation.setFormFieldPath(formFieldPath);
            observation.setFormNamespace(FORM_NAMESPACE);
        }
    }

    private List<String> getCSVHeaderPartsByIgnoringForm2KeyWord(KeyValue csvObservation) {
        final List<String> csvHeaderParts = csvObservationHelper.getCSVHeaderParts(csvObservation);
        // removes form2 keyword
        csvHeaderParts.remove(0);
        return csvHeaderParts;
    }

    private List<String> getConceptNames(List<String> form2CSVHeaderParts) {
        return asList(getLastItem(form2CSVHeaderParts));
    }

    private void processMultiSelectObs(EncounterRow encounterRow, List<EncounterTransaction.Observation> form2Observations, KeyValue form2CSVObservation, List<String> form2CSVHeaderParts) throws ParseException {
        List<String> multiSelectValues = csvObservationHelper.getMultiSelectObs(form2CSVObservation);
        List<KeyValue> multiSelectCSVObservations = processMultipleValues(encounterRow, form2Observations, form2CSVObservation, form2CSVHeaderParts, multiSelectValues);
        setFormNamespaceAndFieldPathForMultiSelectObs(form2Observations, form2CSVHeaderParts, multiSelectCSVObservations);
    }

    private void processAddmoreConcept(EncounterRow encounterRow, List<EncounterTransaction.Observation> form2Observations, KeyValue form2CSVObservation, List<String> form2CSVHeaderParts) throws ParseException {
        List<String> multiSelectValues = csvObservationHelper.getAddmoreObs(form2CSVObservation);
        List<KeyValue> addmoreCSVObservations = processMultipleValues(encounterRow, form2Observations, form2CSVObservation, form2CSVHeaderParts, multiSelectValues);
        setFormNamespaceAndFieldPathForAddmoreObs(form2Observations, form2CSVHeaderParts, addmoreCSVObservations);
    }

    private List<KeyValue> processMultipleValues(EncounterRow encounterRow, List<EncounterTransaction.Observation> form2Observations, KeyValue form2CSVObservation, List<String> form2CSVHeaderParts, List<String> multipleValues) throws ParseException {
        List<KeyValue> form2CSVObservations = new ArrayList<>();
        for (String value : multipleValues) {
            KeyValue newForm2CSVObservation = new KeyValue();
            newForm2CSVObservation.setKey(form2CSVObservation.getKey());
            newForm2CSVObservation.setValue(value.trim());
            form2CSVObservations.add(newForm2CSVObservation);
        }
        csvObservationHelper.createObservations(form2Observations, encounterRow.getEncounterDate(),
                form2CSVObservations, getConceptNames(form2CSVHeaderParts));
        return form2CSVObservations;
    }

    private void setFormNamespaceAndFieldPathForMultiSelectObs(List<EncounterTransaction.Observation> form2Observations, List<String> form2CSVHeaderParts, List<KeyValue> addmoreForm2CSVObservations) {
        if (isEmpty(form2Observations)) {
            return;
        }
        int prevObsCount = form2Observations.size() - addmoreForm2CSVObservations.size();
        for(int i = 0; i < addmoreForm2CSVObservations.size(); i++) {
            final EncounterTransaction.Observation observation = form2Observations.get(prevObsCount + i);
            final String formFieldPath = formFieldPathService.getFormFieldPath(form2CSVHeaderParts);
            observation.setFormFieldPath(formFieldPath);
            observation.setFormNamespace(FORM_NAMESPACE);
        }
    }

    private void setFormNamespaceAndFieldPathForAddmoreObs(List<EncounterTransaction.Observation> form2Observations, List<String> form2CSVHeaderParts, List<KeyValue> addmoreForm2CSVObservations) {
        if (isEmpty(form2Observations)) {
            return;
        }
        int prevObsCount = form2Observations.size() - addmoreForm2CSVObservations.size();
        final String formFieldPath = formFieldPathService.getFormFieldPath(form2CSVHeaderParts);
        String[] tokens = formFieldPath.split(FORM_FIELD_PATH_SEPARATOR);
        int formFieldPathPosition = tokens.length;
        formFieldPathPosition = tokens.length - 1;
        String path = tokens[formFieldPathPosition];
        String controlIdPrefix = path.split("-")[0];

        for(int i = 0; i < addmoreForm2CSVObservations.size(); i++) {
            final EncounterTransaction.Observation observation = form2Observations.get(prevObsCount + i);
            tokens[formFieldPathPosition] = controlIdPrefix + "-" + i;
            observation.setFormFieldPath(String.join(FORM_FIELD_PATH_SEPARATOR, tokens));
            observation.setFormNamespace(FORM_NAMESPACE);
        }
    }

    private void validateObsForFutureDate(List<EncounterTransaction.Observation> form2Observations, KeyValue form2CSVObservation, List<String> form2CSVHeaderParts) throws ParseException {
        EncounterTransaction.Observation observation = getLastItem(form2Observations);
        if(DATE.equals(observation.getConcept().getDataType())) {
            boolean isAllowFutureDates = formFieldPathService.isAllowFutureDates(form2CSVHeaderParts);
            if(!isAllowFutureDates) {
                Date todaysDate = CSVUtils.getTodayDate();
                if(todaysDate.before(CSVUtils.getDateFromString((String)observation.getValue()))) {
                    throw new APIException(format("Future date [%s] is not allowed for [%s]", form2CSVObservation.getValue(), form2CSVHeaderParts.get(form2CSVHeaderParts.size()-1)));
                }
            }
        }
    }
}
