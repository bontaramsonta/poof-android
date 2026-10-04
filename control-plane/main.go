package main

import (
	"context"
	"log/slog"
	"os"

	"github.com/aws/aws-lambda-go/lambda"
	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/ssm"
	"github.com/bontaramsonta/poof/exit"
)

const tokenParameter = "/poof/control-plane/token"

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	ctx := context.Background()

	cfg, err := awsconfig.LoadDefaultConfig(ctx)
	if err != nil {
		log.Error("loading AWS config", "error", err.Error())
		os.Exit(1)
	}
	// Read once per cold start; token.sh --rotate forces a new one.
	out, err := ssm.NewFromConfig(cfg).GetParameter(ctx, &ssm.GetParameterInput{
		Name:           aws.String(tokenParameter),
		WithDecryption: aws.Bool(true),
	})
	if err != nil {
		log.Error("reading token parameter", "error", err.Error())
		os.Exit(1)
	}

	regions := map[string]Region{}
	for _, r := range exit.AllRegions() {
		regions[r] = exit.NewProvisionerFromConfig(cfg, r)
	}
	h := NewHandler(aws.ToString(out.Parameter.Value), func(name string) Region { return regions[name] }, log)
	lambda.Start(h.Handle)
}
