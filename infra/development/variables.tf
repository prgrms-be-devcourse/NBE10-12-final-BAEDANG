variable "prefix" {
  description = "Prefix for all resources"
  default     = "baedang"
}

variable "region" {
  description = "region"
  default     = "ap-northeast-2"
}

variable "team_tag" {
  description = "Team Tag"
  default     = "devcos-team04"
}

variable "bucket_name" {
  description = "Bucket Name for Infra Assets"
  default     = "baedang-bucket-1"
}
