#include "util/config.h"

#include <iostream>
#include <vector>

std::shared_ptr<config::ReplicaConfig> config::load_config(std::string &config_file) {
    std::shared_ptr<libconfig::Config> cfg = std::make_shared<libconfig::Config>();
    try {
        cfg->readFile(config_file.c_str());
    }
    catch(const libconfig::FileIOException& e) {
        std::cerr << "I/O error while reading the config file." << std::endl;
        exit(1);
    }
    catch(const libconfig::ParseException& e) {
        std::cerr << "Parse error at " << e.getFile() << ":" << e.getLine()
                  << " - " << e.getError() << std::endl;
        exit(1);
    }

    std::shared_ptr<ReplicaConfig> config = std::make_shared<ReplicaConfig>();

    try {
        config->num_nodes = cfg->lookup("num_nodes");
        config->threshold = cfg->lookup("threshold");

        const libconfig::Setting& root = cfg->getRoot();
        const libconfig::Setting& replicas = root["replicas"];
        uint32_t num_replicas = replicas.getLength();

        if(num_replicas != config->num_nodes)
            std::cerr << "Number of nodes deviates from specified connection information" << std::endl;

        std::vector<std::string> replica_addr;
        replica_addr.reserve(num_replicas);
        for(uint32_t i = 0; i < num_replicas; i++)
            replica_addr.push_back(replicas[i]);

        config->replica_addr = std::make_shared<std::vector<std::string>>(replica_addr);

    } catch(const libconfig::SettingNotFoundException& e) {
        std::cerr << "Required config value missing" << std::endl;
        exit(1);
    }
    return config;
}