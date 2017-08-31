/**
 * This script suggest user to type path to public key for Code Signing.
 */
var inquirer = require("inquirer");
var fs = require("fs");

module.exports = function (platform) {
    return inquirer.prompt({
        "type": "input",
        "name": "publicKeyPath",
        "message": "What is your path to public key file for CodePush Code Signing for " + platform + " (hit <ENTER> to ignore)"
    }).then(function (answer) {
        if (!answer.publicKeyPath) {
            return "public-key-content-here";
        }

        try {
            return fs.readFileSync(answer.publicKeyPath);
        } catch (e) {
            throw new Error("Could not read public key content from path " + answer.publicKeyPath);
        }
    });
}
