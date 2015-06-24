/**
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2014 ForgeRock AS. All rights reserved.
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://forgerock.org/license/CDDLv1.0.html
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at http://forgerock.org/license/CDDLv1.0.html
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 */

/*global define, $, _, Handlebars */

define("org/forgerock/openidm/ui/admin/mapping/BehaviorsView", [
    "org/forgerock/openidm/ui/admin/util/AdminAbstractView",
    "org/forgerock/openidm/ui/admin/mapping/MappingBaseView",
    "org/forgerock/commons/ui/common/main/EventManager",
    "org/forgerock/commons/ui/common/util/Constants",
    "org/forgerock/openidm/ui/common/delegates/ConfigDelegate",
    "org/forgerock/openidm/ui/admin/delegates/SchedulerDelegate",
    "org/forgerock/openidm/ui/admin/util/Scheduler",
    "org/forgerock/openidm/ui/admin/mapping/behaviors/PoliciesView",
    "org/forgerock/openidm/ui/admin/mapping/behaviors/SituationalEventScriptsView",
    "org/forgerock/openidm/ui/admin/mapping/behaviors/ReconciliationScriptView",
    "org/forgerock/openidm/ui/admin/delegates/BrowserStorageDelegate",
    "org/forgerock/openidm/ui/admin/mapping/behaviors/SingleRecordReconciliationView",
    "org/forgerock/openidm/ui/admin/util/MappingUtils"
], function(AdminAbstractView,
            MappingBaseView,
            eventManager,
            constants,
            ConfigDelegate,
            SchedulerDelegate,
            Scheduler,
            PoliciesView,
            SituationalEventScriptsView,
            ReconciliationScriptView,
            BrowserStorageDelegate,
            SingleRecordReconciliationView,
            mappingUtils) {

    var BehaviorsView = AdminAbstractView.extend({
        template: "templates/admin/mapping/BehaviorsTemplate.html",
        element: "#mappingContent",
        noBaseTemplate: true,
        events: {},
        mapping: null,

        render: function (args, callback) {
            this.data.docHelpUrl = constants.DOC_URL;
            this.model = {
                args: args,
                callback: callback
            };
            MappingBaseView.child = this;
            MappingBaseView.render(args,_.bind(function(){
                this.loadData(args, callback);
            }, this));
        },

        loadData: function(args, callback){
            this.sync = MappingBaseView.data.syncConfig;
            this.recon = MappingBaseView.currentMapping().recon;
            this.mapping = _.omit(MappingBaseView.currentMapping(),"recon");

            this.data.mappingName = this.mappingName = args[0];
            this.data.hideSituational = true;
            this.data.hideRecon = true;
            this.data.hideSingleRecordRecon = mappingUtils.readOnlySituationalPolicy(this.mapping.policies);

            _.each(SituationalEventScriptsView.model.scripts, function(script) {
                if (_.has(SituationalEventScriptsView.model, "mapping")) {
                    if (_.has(SituationalEventScriptsView.model.mapping, script)) {
                        this.data.hideSituational = false;
                    }
                } else if (_.has(this.mapping, script)) {
                    this.data.hideSituational = false;
                }
            }, this);
            _.each(ReconciliationScriptView.model.scripts, function(script) {
                if (_.has(ReconciliationScriptView.model, "mapping")) {
                    if (_.has(ReconciliationScriptView.model.mapping, script)) {
                        this.data.hideRecon = false;
                    }
                } else if (_.has(this.mapping, script)) {
                    this.data.hideRecon = false;
                }
            }, this);

            this.parentRender(_.bind(function() {
                PoliciesView.render({
                    sync: this.sync,
                    mapping: this.mapping,
                    mappingName: this.data.mappingName,
                    saveCallback: _.bind(function () {
                       this.render(this.model.args, this.model.callback);
                    }, this)
                });
                SituationalEventScriptsView.render({sync: this.sync, mapping: this.mapping, mappingName: this.data.mappingName});
                ReconciliationScriptView.render({sync: this.sync, mapping: this.mapping, mappingName: this.data.mappingName});
                SingleRecordReconciliationView.render({sync: this.sync, mapping: this.mapping, mappingName: this.data.mappingName, recon: MappingBaseView.data.recon});
                MappingBaseView.moveSubmenu();

                if(callback){
                    callback();
                }
            }, this));
        },

        saveLiveSync: function() {
            var mapping;
            _.each(this.sync.mappings, function(map, key) {
                if (map.name === this.mappingName) {
                    this.sync.mappings[key].enableSync = this.$el.find(".liveSyncEnabled").prop("checked");
                    mapping = map;
                }
            }, this);

            ConfigDelegate.updateEntity("sync", this.sync).then(_.bind(function() {
                BrowserStorageDelegate.set("currentMapping",  _.extend(mapping,this.recon));
                eventManager.sendEvent(constants.EVENT_DISPLAY_MESSAGE_REQUEST, "syncLiveSyncSaveSuccess");
            }, this));
        },

        reconDeleted: function() {
            $("#addNew").show();
        }
    });

    return new BehaviorsView();
});