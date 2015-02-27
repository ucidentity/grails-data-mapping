/* Copyright (C) 2014 SpringSource
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package grails.mongodb.bootstrap

import com.mongodb.DBAddress
import com.mongodb.Mongo
import com.mongodb.MongoClientURI
import com.mongodb.MongoClientOptions
import grails.core.GrailsApplication
import groovy.transform.CompileStatic
import org.grails.datastore.gorm.bootstrap.AbstractDatastoreInitializer
import org.grails.datastore.gorm.mongo.MongoGormEnhancer
import org.grails.datastore.gorm.mongo.bean.factory.DefaultMappingHolder
import org.grails.datastore.gorm.mongo.bean.factory.GMongoFactoryBean
import org.grails.datastore.gorm.mongo.bean.factory.MongoClientOptionsFactoryBean
import org.grails.datastore.gorm.mongo.bean.factory.MongoDatastoreFactoryBean
import org.grails.datastore.gorm.mongo.bean.factory.MongoMappingContextFactoryBean
import org.grails.datastore.mapping.transactions.DatastoreTransactionManager
import org.grails.spring.beans.factory.InstanceFactoryBean
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.context.ApplicationContext
import org.springframework.context.support.GenericApplicationContext
import org.springframework.data.mongodb.core.MongoOptionsFactoryBean

/**
 * Used to initialize GORM for MongoDB outside of Grails
 *
 * @author Graeme Rocher
 * @since 2.0
 */
class MongoDbDataStoreSpringInitializer extends AbstractDatastoreInitializer{

    public static final String DEFAULT_DATABASE_NAME = "test"
    public static final String SETTING_CONNECTION_STRING = 'grails.mongodb.connectionString'
    public static final String SETTING_DEFAULT_MAPPING = 'grails.mongodb.default.mapping'
    public static final String SETTING_OPTIONS = 'grails.mongodb.options'
    public static final String SETTING_HOST = 'grails.mongodb.host'
    public static final String SETTING_PORT = 'grails.mongodb.port'
    public static final String SETTING_USERNAME = 'grails.mongodb.username'
    public static final String SETTING_PASSWORD = 'grails.mongodb.password'
    public static final String SETTING_REPLICA_SET = 'grails.mongodb.replicaSet'
    public static final String SETTING_REPLICA_PAIR = 'grails.mongodb.replicaPair'

    protected String mongoBeanName = "mongo"
    protected String mongoOptionsBeanName = "mongoOptions"
    protected String databaseName = DEFAULT_DATABASE_NAME
    protected Closure defaultMapping
    protected MongoClientOptions mongoOptions
    protected Mongo mongo


    MongoDbDataStoreSpringInitializer() {
    }

    MongoDbDataStoreSpringInitializer(ClassLoader classLoader, String... packages) {
        super(classLoader, packages)
    }

    MongoDbDataStoreSpringInitializer(String... packages) {
        super(packages)
    }

    MongoDbDataStoreSpringInitializer(Collection<Class> persistentClasses) {
        super(persistentClasses)
    }

    MongoDbDataStoreSpringInitializer(Class... persistentClasses) {
        super(persistentClasses)
    }

    MongoDbDataStoreSpringInitializer(Map configuration, Collection<Class> persistentClasses) {
        super(configuration, persistentClasses)
    }

    MongoDbDataStoreSpringInitializer(Map configuration, Class... persistentClasses) {
        super(configuration, persistentClasses)
    }

    /**
     * Configures for an existing Mongo instance
     * @param mongo The instance of Mongo
     * @return The configured ApplicationContext
     */
    @CompileStatic
    ApplicationContext configure() {
        ExpandoMetaClass.enableGlobally()
        GenericApplicationContext applicationContext = new GenericApplicationContext()
        applicationContext.beanFactory.registerSingleton( mongoBeanName, mongo)
        configureForBeanDefinitionRegistry(applicationContext)
        applicationContext.refresh()
        return applicationContext
    }

    @Override
    Closure getBeanDefinitions(BeanDefinitionRegistry beanDefinitionRegistry) {
        return {
            String connectionString = configurationObject.getProperty(SETTING_CONNECTION_STRING,'') ?: null
            Closure defaultMapping = configurationObject.getProperty(SETTING_DEFAULT_MAPPING,Closure, this.defaultMapping)
            Map mongoOptions = configurationObject.getProperty(SETTING_OPTIONS, Map, null)
            String hostSetting = configurationObject.getProperty(SETTING_HOST, '')
            Integer mongoPort = configurationObject.getProperty(SETTING_PORT, Integer, null)
            String username = configurationObject.getProperty(SETTING_USERNAME, '')
            String password= configurationObject.getProperty(SETTING_PASSWORD, '')
            Collection<String> replicaSetSetting = configurationObject.getProperty(SETTING_REPLICA_SET, Collection, [])
            Collection<String> replicaPairSetting = configurationObject.getProperty(SETTING_REPLICA_PAIR, Collection, [])

            MongoClientURI mongoClientURI = null
            if(connectionString) {
                mongoClientURI = new MongoClientURI(connectionString)
                databaseName = mongoClientURI.database
            }

            def callable = getCommonConfiguration(beanDefinitionRegistry)
            callable.delegate = delegate
            callable.call()

            gormMongoMappingContext(MongoMappingContextFactoryBean) {
                defaultDatabaseName = databaseName
                grailsApplication = ref(GrailsApplication.APPLICATION_ID)
                if (defaultMapping) {
                    delegate.defaultMapping = new DefaultMappingHolder(defaultMapping)
                }
            }

            if(this.mongoOptions) {
                "$mongoOptionsBeanName"(InstanceFactoryBean, this.mongoOptions, MongoClientOptions)
            }
            else if(!beanDefinitionRegistry.containsBeanDefinition(mongoOptionsBeanName)) {
                "$mongoOptionsBeanName"(MongoClientOptionsFactoryBean) {
                    if(mongoOptions) {
                        delegate.mongoOptions = mongoOptions
                    }
                }
            }

            if(mongo) {
                "$mongoBeanName"(InstanceFactoryBean, mongo)
            }
            else  {

                def existingBean = beanDefinitionRegistry.containsBeanDefinition(mongoBeanName) ? beanDefinitionRegistry.getBeanDefinition(mongoBeanName) : null
                boolean registerMongoBean = false
                if(existingBean instanceof AnnotatedBeanDefinition) {
                    AnnotatedBeanDefinition annotatedBeanDefinition = (AnnotatedBeanDefinition)existingBean
                    if(annotatedBeanDefinition.metadata.className == 'org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration') {
                        registerMongoBean = true
                    }
                }
                else if(existingBean == null) {
                    registerMongoBean = true
                }

                if(registerMongoBean) {
                    "gmongo"(GMongoFactoryBean) {
                        delegate.mongoOptions = ref("$mongoOptionsBeanName")
                        if(username && password) {
                            delegate.username = username
                            delegate.password = password
                        }

                        if(mongoClientURI) {
                            clientURI = mongoClientURI
                        }
                        else if (replicaSetSetting) {
                            def set = []
                            for (server in replicaSetSetting) {
                                set << new DBAddress(server.indexOf("/") > 0 ? server : "$server/$databaseName")
                            }

                            replicaSetSeeds = set
                        }
                        else if (replicaPairSetting) {
                            def pair = []
                            for (server in replicaPairSetting) {
                                pair << new DBAddress(server.indexOf("/") > 0 ? server : "$server/$databaseName")
                            }
                            replicaPair = pair
                        }
                        else if (hostSetting) {
                            host = hostSetting
                            if (mongoPort) port = mongoPort
                        }
                        else {
                            host = "localhost"
                        }
                    }

                    "$mongoBeanName"(gmongo:"getMongoClient")
                }

            }
            mongoDatastore(MongoDatastoreFactoryBean) {
                delegate.mongo = ref(mongoBeanName)
                mappingContext = gormMongoMappingContext
                config = configurationObject
            }
            "mongoTransactionManager"(DatastoreTransactionManager) {
                datastore = ref("mongoDatastore")
            }

            "org.grails.gorm.mongodb.internal.GORM_ENHANCER_BEAN-${mongoBeanName}"(MongoGormEnhancer, ref("mongoDatastore"), ref("mongoTransactionManager")) { bean ->
                bean.initMethod = 'enhance'
                bean.lazyInit = false
            }
        }
    }

    /**
     * Sets the name of the Mongo bean to use
     */
    void setMongoBeanName(String mongoBeanName) {
        this.mongoBeanName = mongoBeanName
    }
    /**
     * The name of the MongoOptions bean
     *
     * @param mongoOptionsBeanName The mongo options bean name
     */
    void setMongoOptionsBeanName(String mongoOptionsBeanName) {
        this.mongoOptionsBeanName = mongoOptionsBeanName
    }
    /**
     * Sets the MongoOptions instance to use when constructing the Mongo instance
     */
    void setMongoOptions(MongoClientOptions mongoOptions) {
        this.mongoOptions = mongoOptions
    }
    /**
     * Sets a pre-existing Mongo instance to configure for
     * @param mongo The Mongo instance
     */
    void setMongo(Mongo mongo) {
        this.mongo = mongo
    }
    /**
     * Sets the name of the MongoDB database to use
     */
    void setDatabaseName(String databaseName) {
        this.databaseName = databaseName
    }

    /**
     * Sets the default MongoDB GORM mapping configuration
     */
    void setDefaultMapping(Closure defaultMapping) {
        this.defaultMapping = defaultMapping
    }
}
