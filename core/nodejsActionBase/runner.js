/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Object which encapsulates a first-class function, the user code for
 * an action.
 *
 * This file (runner.js) must currently live in root directory for nodeJsAction.
 */
var util = require('util');
var child_process = require('child_process');
var fs = require('fs');
var path = require('path');

function NodeActionRunner() {
    // Use this ref inside closures etc.
    var thisRunner = this;

    this.userScriptMain = undefined;

    // This structure is reset for every action invocation. It contains two fields:
    //   - completed; indicating whether the action has already signaled completion
    //   - next; a callback to be invoked with the result of the action.
    // Note that { error: ... } results are still results.
    var callback = {
        completed : undefined,
        next      : function (result) { return; }
    };

    this.init = function(message) {
        function makeModule(message) {
            if (message.binary) {
                // The code is a base64-encoded zip file.
                return unzipInTmpDir(message.code).then(function (moduleDir) {
                    return 'require("' + moduleDir + '").' + message.main;
                });
            } else {
                var code = message.code + '\nmodule.exports = ' + message.main;
                // The code is a plain old JS file.
                return writeInTmpFile("action.js", code, "utf8").then(function (moduleFile) {
                    return 'require("' + moduleFile + '")';
                });
            }
        }

        function assertMainIsFunction() {
            if (typeof thisRunner.userScriptMain !== 'function') {
                throw "Action entrypoint '" + message.main + "' is not a function.";
            }
        }

        // Loading the user code.
        return makeModule(message).then(function (mainFun) {
            try {
                thisRunner.userScriptMain = eval(mainFun);
                assertMainIsFunction();
                // The value 'true' has no special meaning here;
                // the successful state is fully reflected in the
                // successful resolution of the promise.
                return true;
            } catch (e) {
                return Promise.reject(e);
            }
        }).catch(function (error) {
            return Promise.reject(error);
        });
    };

    // Returns a Promise with the result of the user code invocation.
    // The Promise is rejected iff the user code throws.
    this.run = function(args) {
        return new Promise(
            function (resolve, reject) {
                callback.completed = undefined;
                callback.next = resolve;

                try {
                    var result = thisRunner.userScriptMain(args);
                } catch (e) {
                    reject(e);
                }

                // Non-promises/undefined instantly resolve.
                Promise.resolve(result).then(function (resolvedResult) {
                    // This happens, e.g. if you just have "return;"
                    if (typeof resolvedResult === "undefined") {
                        resolvedResult = {};
                    }
                    resolve(resolvedResult);
                }).catch(function (error) {
                    // A rejected Promise from the user code maps into a
                    // successful promise wrapping a whisk-encoded error.

                    // Special case if the user just called `reject()`.
                    if (!error) {
                        resolve({ error: {}});
                    } else {
                        resolve({ error: error });
                    }
                });
            }
        );
    };

    // Helper function to copy a base64-encoded zip file to a temporary location,
    // decompress it into temporary directory, and return the name of that directory.
    // Note that this makes heavy use of shell commands because:
    //   1) Node 0.12 doesn't have many of the useful fs functions.
    //   2) We know in which environment we're running.
    function unzipInTmpDir(base64) {
        return writeInTmpFile("action.zip", base64, "base64").then(function (zipFile) {
            return exec("mktemp -d XXXXXXXX").then(function (tmpDir) {
                return exec("unzip -qq " + zipFile + " -d " + tmpDir).then(function (res) {
                   return path.resolve(tmpDir);
                }).catch(function (error) {
                   return Promise.reject("There was an error uncompressing the action archive.");
                });
            });
        });
    }

    // Helper function to run shell commands.
    function exec(cmd) {
        return new Promise(
            function (resolve, reject) {
                child_process.exec(cmd, function (error, stdout, stderr) {
                    if(error) {
                        reject(stderr.trim());
                    } else {
                        resolve(stdout.trim());
                    }
                });
            }
        );
    }

    // Helper function to write content to a file in a temporary directory
    // and return the path of that file.
    function writeInTmpFile(fileName, content, encoding) {
        return exec("mktemp -d XXXXXXXX").then(function (tmpDir) {
            var tmpFile = path.join(tmpDir, fileName);
            return new Promise(
                function (resolve, reject) {
                    fs.writeFile(tmpFile, content, encoding, function (err) {
                        if(err) {
                            reject("There was an error writing the action file.");
                        }
                        resolve(path.resolve(tmpFile));
                    });
                }
            );
        });
    }
}

module.exports = NodeActionRunner;
