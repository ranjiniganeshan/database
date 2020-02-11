def call(body) {
	// evaluate the body block, and collect configuration into the object
	def config = [:]
    def log = new com.sap.ms.L()
	body.resolveStrategy = Closure.DELEGATE_FIRST
	body.delegate = config
	body()
	def fileUtils = new com.sap.ms.FileUtils()

	String jobName = env.BUILD_TAG
	String workarea = "workarea/db/${env.BUILD_NUMBER}"
	String dbHcl = "${workarea}/db.hcl"
	fileUtils.mkdir(workarea)
	withCredentials([usernamePassword(credentialsId: 'registryCredentials',
		passwordVariable: 'REGISTRY_PASSWORD', usernameVariable: 'REGISTRY_USER')]) {
		writeFile file: dbHcl, text: """
			job "${jobName}" {
		  datacenters = ["db-dc"]
		  type        = "service"

		  group "db" {
		    count = 1

		    restart {
		      attempts = 2
		      delay    = "15s"
		      interval = "1m"
		      mode     = "delay"
		    }

		    ephemeral_disk {
		      size = 200
		    }

		    task "oracle" {
		      driver = "docker"

		      config {
		        image = "${env.DOCKER_REGISTRY}/mine/oracle:12"

		        port_map = {
		          db_port = 1521
		        }

		        auth {
		          username = "${env.REGISTRY_USER}"
		          password = "${env.REGISTRY_PASSWORD}"
		        }
		      }

		      resources {
		        cpu    = 500
		        memory = 4096

		        network {
		          mbits = 10
		          port  "db_port"{}
		        }
		      }
		    }
		  }
		}

		"""
	}
	def nomadStatus = sh script: "export NOMAD_ADDR=http://db-cluster.ran.corp:4646; nomad run ${dbHcl}", returnStatus: true

	//Shell returns zero for success
	if(!nomadStatus) {
		String dbCluster = "http://db-cluster.ran.corp:4646/v1"
		def allocations = com.ran.ms.Curl.GetJsonData("${dbCluster}/allocations")
		log.info("Checking allocations")
		for(def ic = 0; ic < allocations.size(); ic++) {
			def allocation = allocations[ic]
			if(allocation.JobID.equals(jobName)) {
				log.info("Located the db allocation. Fetching the connection parameters")
				def allocationDetails = com.ran.ms.Curl.GetJsonData("${dbCluster}/allocation/${allocation.ID}")
				String DB_PORT = allocationDetails.Resources.Networks[0].DynamicPorts[0].Value
				String DB_IP = allocationDetails.Resources.Networks[0].IP
				String SID = "XE"
				log.info("Invoking user step")

				fileUtils = null
				log = null
				allocation = null
				allocationDetails = null
				allocations = null
				config.step(DB_IP, DB_PORT, SID)
				sh script: "export NOMAD_ADDR=http://db-cluster.ran.corp:4646; nomad stop ${jobName}"
				return
			}
		}
		log.error("Failed to initialize DB instance, no allocation detected")
	} else {
		log.error("Failed to deploy database container")
	}
}
