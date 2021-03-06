package org.grails.datastore.gorm.validation;

import grails.core.GrailsDomainClass;
import grails.core.GrailsDomainClassProperty;
import org.grails.core.artefact.DomainClassArtefactHandler;
import org.grails.datastore.gorm.GormValidateable;
import org.grails.datastore.gorm.support.BeforeValidateHelper;
import org.grails.datastore.mapping.core.Datastore;
import org.grails.datastore.mapping.proxy.JavassistProxyFactory;
import org.grails.datastore.mapping.proxy.ProxyFactory;
import org.grails.validation.GrailsDomainClassValidator;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.MessageSourceAware;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;

import java.util.ArrayList;

/**
 * A validator aware of proxies and that integrates with Grails' cascading validation
 *
 * @author Graeme Rocher
 * @since 5.0
 */
public class DefaultDomainClassValidator extends GrailsDomainClassValidator implements MessageSourceAware, CascadingValidator, grails.validation.CascadingValidator, ApplicationContextAware {

    private final BeforeValidateHelper beforeValidateHelper = new BeforeValidateHelper();
    private ProxyFactory proxyHandler = new JavassistProxyFactory();
    private Datastore datastore;
    private String datastoreName;
    private ApplicationContext applicationContext;

    public BeforeValidateHelper getBeforeValidateHelper() {
        return beforeValidateHelper;
    }

    public void setDatastoreName(String datastoreName) {
        this.datastoreName = datastoreName;
    }

    public Datastore getDatastore() {
        if(datastoreName == null) {
            throw new IllegalStateException("Not datastore name configured. Please provide one");
        }
        if(datastore != null) {
            return datastore;
        }
        else {
            datastore = applicationContext.getBean(datastoreName, Datastore.class);
            return datastore;
        }
    }

    @Override
    protected GrailsDomainClass getAssociatedDomainClassFromApplication(Object associatedObject) {
        String associatedObjectType = proxyHandler.getProxiedClass(associatedObject).getName();
        return (GrailsDomainClass) grailsApplication.getArtefact(DomainClassArtefactHandler.TYPE, associatedObjectType);
    }

    @Autowired(required = false)
    public void setProxyFactory(ProxyFactory proxyHandler) {
        this.proxyHandler = proxyHandler;
    }

    /**
     * Overrides the default behaviour and first checks if a PersistentCollection instance has been initialised using the
     * wasInitialised() method before cascading
     *
     * @param errors The Spring Errors instance
     * @param bean The BeanWrapper for the bean
     * @param persistentProperty The GrailsDomainClassProperty instance
     * @param propertyName The name of the property
     *
     * @see org.hibernate.collection.PersistentCollection#wasInitialized()
     */
    @Override
    protected void cascadeValidationToMany(Errors errors, BeanWrapper bean, GrailsDomainClassProperty persistentProperty, String propertyName) {
        Object collection = bean.getPropertyValue(propertyName);
        if (collection == null) {
            return;
        }

        if (proxyHandler.isProxy(collection)) {
            if (proxyHandler.isInitialized(collection)) {
                super.cascadeValidationToMany(errors, bean, persistentProperty, propertyName);
            }
        }
        else {
            super.cascadeValidationToMany(errors, bean, persistentProperty, propertyName);
        }
    }

    @Override
    protected void cascadeValidationToOne(Errors errors, BeanWrapper bean, Object associatedObject, GrailsDomainClassProperty persistentProperty, String propertyName, Object indexOrKey) {
        if(proxyHandler.isInitialized(associatedObject)) {
            associatedObject = proxyHandler.isProxy(associatedObject) ? proxyHandler.unwrap(associatedObject) : associatedObject;
            if(associatedObject != null) {
                cascadeBeforeValidate(associatedObject);
                GrailsDomainClass associatedDomainClass = getAssociatedDomainClass(associatedObject, persistentProperty);
                if (associatedDomainClass == null || !isOwningInstance(bean, associatedDomainClass) && !persistentProperty.isExplicitSaveUpdateCascade()) {
                    if(associatedObject instanceof GormValidateable && (persistentProperty.isOneToOne() || persistentProperty.isManyToOne())) {
                        GormValidateable validateable = (GormValidateable) associatedObject;
                        Errors existingErrors = validateable.getErrors();
                        if(existingErrors != null && existingErrors.hasErrors()) {
                            for(FieldError error : existingErrors.getFieldErrors()) {
                                String path = propertyName + '.' + error.getField();
                                errors.rejectValue(path, error.getCode(), error.getArguments(), error.getDefaultMessage());
                            }
                        }
                    }
                }
                else {
                    super.cascadeValidationToOne(errors, bean, associatedObject, persistentProperty, propertyName, indexOrKey);
                }
            }
        }
    }

    protected void cascadeBeforeValidate(Object associatedObject) {
        final GrailsDomainClass associatedDomainClass = getAssociatedDomainClassFromApplication(associatedObject);
        if(associatedDomainClass != null) {
            getBeforeValidateHelper().invokeBeforeValidate(associatedObject, new ArrayList<Object>(associatedDomainClass.getConstrainedProperties().keySet()));
        }
    }

    private GrailsDomainClass getAssociatedDomainClass(Object associatedObject, GrailsDomainClassProperty persistentProperty) {
        if (persistentProperty.isEmbedded()) {
            return persistentProperty.getComponent();
        }

        if (grailsApplication != null) {
            return getAssociatedDomainClassFromApplication(associatedObject);
        }

        return persistentProperty.getReferencedDomainClass();
    }

    private boolean isOwningInstance(BeanWrapper bean, GrailsDomainClass associatedDomainClass) {
        Class<?> currentClass = bean.getWrappedClass();
        while (currentClass != Object.class) {
            if (associatedDomainClass.isOwningClass(currentClass)) {
                return true;
            }
            currentClass = currentClass.getSuperclass();
        }
        return false;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}

