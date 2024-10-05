#ifndef THRESHSIG_WRAPPER_CONFIG_H
#define THRESHSIG_WRAPPER_CONFIG_H

#include <memory>
#include <libconfig.h++>

namespace config {
    struct ReplicaConfig {
        uint32_t num_nodes;
        uint32_t threshold;
        std::shared_ptr<std::vector<std::string>> replica_addr;
    };

    std::shared_ptr<ReplicaConfig> load_config(std::string& config_file);
};

#endif //THRESHSIG_WRAPPER_CONFIG_H
